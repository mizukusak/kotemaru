package org.kotemaru.android.async.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.message.AbstractHttpMessage;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.kotemaru.android.async.BuildConfig;
import org.kotemaru.android.async.ByteBufferReader;
import org.kotemaru.android.async.ByteBufferWriter;
import org.kotemaru.android.async.SelectorItem;
import org.kotemaru.android.async.SelectorItemPool;
import org.kotemaru.android.async.SelectorThread;
import org.kotemaru.android.async.SelectorItem.SelectorItemListener;
import org.kotemaru.android.async.helper.PartByteArrayInputStream;
import org.kotemaru.android.async.helper.PartConsumer;
import org.kotemaru.android.async.helper.PartInputStream;
import org.kotemaru.android.async.helper.PartProducer;
import org.kotemaru.android.async.helper.WritableListener;
import org.kotemaru.android.async.http.body.ChunkedReadFilter;
import org.kotemaru.android.async.http.body.ChunkedWriteFilter;
import org.kotemaru.android.async.http.body.StreamReadFilter;
import org.kotemaru.android.async.http.body.StreamWriteFilter;

import android.util.Log;

/**
 * HTTPリスエストの共通部。
 * @author kotemaru.org
 */
public abstract class AsyncHttpRequest
		extends AbstractHttpMessage
		implements SelectorItemListener, HttpUriRequest {
	private static final String TAG = AsyncHttpRequest.class.getSimpleName();
	private static final boolean IS_DEBUG = BuildConfig.DEBUG;

	public enum MethodType {
		GET, POST
	}

	public enum State {
		PREPARE, CONNECT,
		REQUEST_HEADER, REQUSET_BODY,
		RESPONSE_HEADER, RESPONSE_BODY,
		DONE, ERROR
	}

	private AsyncHttpClient mHttpClient;
	private URI mUri;
	private AsyncHttpListener mAsyncHttpListener;
	private BasicHttpResponse mHttpResponse;
	private SingleByteBufferReader mSingleBufferReader;

	private WritableListener mRequestBodyWriter;
	private PartConsumer mResponseBodyConsumer;

	private int mBufferSize = 4096;
	private ByteBuffer mBuffer;
	private SelectorItem mSelectorItem;

	private State mState = State.PREPARE;
	private boolean mIsAborted;

	public AsyncHttpRequest() {
	}
	public AsyncHttpRequest(URI uri) {
		setURI(uri);
	}

	/**
	 * リクエスト開始。
	 * - 開始要求するだけで通信は行わない。
	 * @param httpClient 親クライアント
	 * @param listener
	 * @throws IOException 初期化に失敗。
	 */
	public void execute(AsyncHttpClient httpClient, AsyncHttpListener listener) throws IOException {
		initState();
		mHttpClient = httpClient;
		mAsyncHttpListener = listener;

		SelectorThread selector = SelectorThread.getInstance();
		String host = mUri.getHost();
		int port = HttpUtil.getPort(mUri);
		boolean isSsl = HttpUtil.isHttps(mUri);
		this.setHeader(new BasicHeader("Host", host + ":" + port));
		selector.openSocketClient(host, port, isSsl, this);
	}

	/**
	 * 内部状態の取得。エラー処理のヒントに使用。
	 * @return 内部状態。
	 */
	public State getState() {
		return mState;
	}

	/**
	 * 内部バッファのサイズを設定。
	 * - 初期値は 4096。
	 * - 送受信共にHTTPヘッダはこのサイズに制限される。
	 * @param bufferSize
	 */
	public void setBufferSize(int bufferSize) {
		mBufferSize = bufferSize;
	}
	public int getBufferSize() {
		return mBufferSize;
	}

	// -----------------------------------------------------------------------------
	// abstract methods.
	// -----------------------------------------------------------------------------
	public abstract MethodType getMethodType();
	public abstract HttpEntity getHttpEntity();

	// -----------------------------------------------------------------------------
	// for implements org.kotemaru.android.async.HttpMessage
	// -----------------------------------------------------------------------------
	@Override
	public ProtocolVersion getProtocolVersion() {
		return HttpUtil.PROTOCOL_VERSION;
	}

	@Override
	public void abort() throws UnsupportedOperationException {
		mIsAborted = true;
		if (mSelectorItem != null) {
			doFinish(true);
		}
	}
	@Override
	public boolean isAborted() {
		return mIsAborted;
	}

	@Override
	public String getMethod() {
		return getMethodType().name();
	}
	@Override
	public URI getURI() {
		return mUri;
	}
	public void setURI(URI uri) {
		mUri = uri;
	}

	// -----------------------------------------------------------------------------
	// for implements SelectorItemListener
	// -----------------------------------------------------------------------------
	@Override
	public void onRegister(SelectorItem item) {
		if (IS_DEBUG) Log.v(TAG, "onRegister:");
		mSelectorItem = item;
		mSelectorItem.setListener(this);
	}
	@Override
	public void onConnect() {
		if (IS_DEBUG) Log.v(TAG, "onConnect:");
		mSingleBufferReader = new SingleByteBufferReader(mSelectorItem);

		setState(State.CONNECT, SelectorItem.OP_NONE);
		mAsyncHttpListener.onConnect(this);  // do Callback.
		startRequestHeader();
	}

	@Override
	public void onWritable() {
		if (IS_DEBUG) Log.v(TAG, "onWritable:" + mState);
		if (mState == State.REQUEST_HEADER) {
			if (mBuffer.hasRemaining()) {
				writeFromBuffer(mBuffer, true);
			} else {
				debugLogHeader("Http-Request", "\n>>> ");
				mAsyncHttpListener.onRequestHeader(this);  // do Callback.
				startRequestBody();
			}
		} else if (mState == State.REQUSET_BODY) {
			try {
				int n = mRequestBodyWriter.onWritable();
				if (n < 0) {
					if (getHttpEntity() != null) {
						mAsyncHttpListener.onRequestBody(this);  // do Callback.
					}
					startResponseHeader();
				}
			} catch (Exception e) {
				doError("Post entity fail", e);
			}
		} else {
			doError("Bad state " + mState + " onWritable().", null);
		}
	}

	@Override
	public void onReadable() {
		if (IS_DEBUG) Log.v(TAG, "onReadable:" + ":" + mState);
		if (mSingleBufferReader.isLocked()) {
			mSingleBufferReader.onReadable();
			return;
		}
		if (mState == State.RESPONSE_HEADER) {
			readIntoBuffer(mBuffer, true);
			mHttpResponse = HttpUtil.parseResponseHeader(mBuffer);
			if (mHttpResponse != null) {
				debugLogHeader("Http-Response", "\n<<< ");
				mAsyncHttpListener.onResponseHeader(mHttpResponse);  // do Callback.
				mHttpClient.setCookies(mHttpResponse, mUri);
				startResponseBody();
			}
		} else if (mState == State.RESPONSE_BODY) {
			int n = readIntoBuffer(mBuffer, false);
			mBuffer.flip();
			doResponseBodyPart(n);
		} else {
			doError("Bad state " + mState + " onReadable().", null);
		}
	}

	@Override
	public void onError(String msg, Throwable t) {
		doError(msg, t);
	}

	// -----------------------------------------------------------------------------
	// for internal logic
	// -----------------------------------------------------------------------------
	private void startRequestHeader() {
		mBuffer.clear();
		HttpEntity entity = getHttpEntity();
		if (entity != null) {
			setRequestHeader(entity.getContentEncoding());
			setRequestHeader(entity.getContentType());
			if (entity.getContentLength() >= 0) {
				setRequestHeader(new BasicHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(entity.getContentLength())));
			} else {
				setRequestHeader(new BasicHeader(HttpHeaders.TRANSFER_ENCODING, HttpUtil.CHUNKED));
			}
		}
		HttpUtil.formatRequestHeader(mBuffer, getMethodType(), mUri, this, mHttpClient);
		mBuffer.flip();
		setState(State.REQUEST_HEADER, SelectionKey.OP_WRITE);
	}

	private void startRequestBody() {

		HttpEntity entity = getHttpEntity();
		if (entity != null) {
			PartProducer endPointProducer;
			if (mAsyncHttpListener.isRequestBodyPart()) {
				endPointProducer = new ResuestBodyPartProducer();
			} else {
				try {
					InputStream content = getHttpEntity().getContent();
					endPointProducer = new StreamRequestBodyPartProducer(content);
				} catch (Exception e) {
					doError("Post entity fail", e);
					return;
				}
			}
			try {
				if (entity.getContentLength() >= 0) {
					mRequestBodyWriter = new StreamWriteFilter(mSelectorItem, endPointProducer);
				} else {
					mRequestBodyWriter = new ChunkedWriteFilter(mSelectorItem, endPointProducer);
				}
			} catch (IOException e) {
				doError("PartWriter init fail.", e);
			}
			mBuffer.clear();
			setState(State.REQUSET_BODY, SelectionKey.OP_WRITE);
		} else {
			startResponseHeader();
		}
	}

	private class ResuestBodyPartProducer implements PartProducer {
		@Override
		public void requestNextPart(ByteBufferWriter transporter) throws IOException {
			mBuffer.clear();
			mAsyncHttpListener.onRequestBodyPart(transporter);  // do Callback.
		}
	};

	private class StreamRequestBodyPartProducer implements PartProducer {
		InputStream mRequestContent;

		public StreamRequestBodyPartProducer(InputStream requestContent) {
			mRequestContent = requestContent;
		}
		@Override
		public void requestNextPart(ByteBufferWriter transporter) throws IOException {
			mBuffer.clear();
			byte[] buff = mBuffer.array();
			int readSize = mRequestContent.read(buff);
			if (readSize > 0) {
				mBuffer.position(readSize);
				mBuffer.flip();
				transporter.write(mBuffer);
			} else {
				transporter.write(null);
			}
		}
	};

	private void startResponseHeader() {
		mBuffer.clear();
		setState(State.RESPONSE_HEADER, SelectionKey.OP_READ);
	}
	private void startResponseBody() {
		PartConsumer endPointConsumer;
		if (mAsyncHttpListener.isResponseBodyPart()) {
			endPointConsumer = new ResponseBodyPartConsumer();
		} else {
			endPointConsumer = new ByteArrayResponseBodyPartConsumer(new PartByteArrayInputStream());
		}

		boolean isChunked = HttpUtil.hasChunkedTransferHeader(mHttpResponse);
		if (isChunked) {
			mResponseBodyConsumer = new ChunkedReadFilter(endPointConsumer);
		} else {
			long contentLength = HttpUtil.getContentLength(mHttpResponse);
			mResponseBodyConsumer = new StreamReadFilter(endPointConsumer, contentLength);
		}
		setState(State.RESPONSE_BODY, SelectionKey.OP_READ);
		if (mBuffer.remaining() > 0) {
			doResponseBodyPart(mBuffer.remaining());
		}
	}

	private class ByteArrayResponseBodyPartConsumer implements PartConsumer {
		private PartInputStream mResponseBodyStream;

		public ByteArrayResponseBodyPartConsumer(PartInputStream responseBodyStream) {
			mResponseBodyStream = responseBodyStream;
		}
		@Override
		public void postPart(ByteBuffer buffer) {
			try {
				mResponseBodyStream.write(buffer);
			} catch (IOException e) {
				doError("Response body write fail.", e);
			}
			if (buffer == null) doResponseBody(mResponseBodyStream);
		}
	}

	private class ResponseBodyPartConsumer implements PartConsumer {
		@Override
		public void postPart(ByteBuffer buffer) {
			mSingleBufferReader.setBuffer(buffer);
			mAsyncHttpListener.onResponseBodyPart(mSingleBufferReader);  // do Callback.
			if (buffer == null) doFinish(false);
		}
	}

	private void doResponseBodyPart(int len) {
		if (len == -1) {
			mResponseBodyConsumer.postPart(null);
		} else {
			mResponseBodyConsumer.postPart(mBuffer);
		}
		mBuffer.clear();
	}
	private void doResponseBody(PartInputStream bodyStream) {
		BasicHttpEntity entity = new BasicHttpEntity();
		entity.setContent(bodyStream);
		if (bodyStream != null) {
			entity.setContentLength(bodyStream.getLength());
		}
		entity.setContentEncoding(mHttpResponse.getFirstHeader(HttpHeaders.CONTENT_ENCODING));
		entity.setContentType(mHttpResponse.getFirstHeader(HttpHeaders.CONTENT_TYPE));

		mHttpResponse.setEntity(entity);
		mAsyncHttpListener.onResponseBody(mHttpResponse);  // do Callback.
		doFinish(false);
	}

	private void doError(String msg, Throwable err) {
		Log.w(TAG, msg, err);
		if (mAsyncHttpListener != null) {
			mAsyncHttpListener.onError(msg, err);
		}
		mState = State.ERROR;
		abort();
	}
	private void doFinish(boolean isDisconnect) {
		setState(State.DONE, SelectorItem.OP_NONE);
		mSelectorItem.release();
		if (isDisconnect) {
			mSelectorItem.close();
		} else {
			SelectorItemPool.getInstance().releaseSelectorItem(mSelectorItem);
		}
		mAsyncHttpListener.onClose(this);
		clearState();
	}

	// -----------------------------------------------------------------------------
	// for internal util.
	// -----------------------------------------------------------------------------

	private static class SingleByteBufferReader implements ByteBufferReader {
		private volatile boolean mIsBufferLocked;
		private SelectorItem mSelectorItem;
		private ByteBuffer mBuffer;

		public SingleByteBufferReader(SelectorItem channel) {
			mSelectorItem = channel;
		}

		public void setBuffer(ByteBuffer buffer) {
			mBuffer = buffer;
		}

		public void onReadable() {
			if (mIsBufferLocked) {
				mSelectorItem.requireOn(SelectorItem.OP_NONE);
			}
		}

		public boolean isLocked() {
			return mIsBufferLocked;
		}

		@Override
		public ByteBuffer read() throws IOException {
			mIsBufferLocked = true;
			return mBuffer;
		}
		@Override
		public void release(ByteBuffer buffer) {
			if (buffer != mBuffer) {
				throw new RuntimeException("Unknown buffer." + buffer);
			}
			mIsBufferLocked = false;
			mSelectorItem.requireOn(SelectorItem.OP_READ);
		}
	};

	private void setState(State state, int ops) {
		mState = state;
		if (mSelectorItem != null) {
			mSelectorItem.requireOn(ops);
		}
	}
	private void initState() throws IOException {
		if (mState != State.PREPARE && mState != State.DONE && mState != State.ERROR) {
			throw new IOException("Can not reuse. Bad status " + mState);
		}
		mState = State.PREPARE;
		mIsAborted = false;
		if (mBuffer == null || mBuffer.capacity() != mBufferSize) {
			mBuffer = ByteBuffer.wrap(new byte[mBufferSize]);
		}
		clearState();
	}
	private void clearState() {
		mHttpClient = null;
		mSelectorItem = null;
		mAsyncHttpListener = null;
		mHttpResponse = null;
	}

	private void setRequestHeader(Header header) {
		if (header == null) return;
		this.setHeader(header);
	}

	private int readIntoBuffer(ByteBuffer buffer, boolean isEofError) {
		try {
			int n = mSelectorItem.read(buffer);
			if (n == 0) {
				Log.w(TAG, "read fail: size=0");
			}
			if (isEofError && n == -1) {
				throw new IOException("EOF");
			}
			return n;
		} catch (IOException e) {
			doError("Read fail:", e);
			return -1;
		}
	}
	private int writeFromBuffer(ByteBuffer buffer, boolean isEofError) {
		try {
			int n = mSelectorItem.write(buffer);
			if (n == 0) {
				Log.w(TAG, "write fail: size=0");
			}
			if (isEofError && n == -1) {
				throw new IOException("EOF");
			}
			return n;
		} catch (IOException e) {
			doError("Write fail:", e);
			return -1;
		}
	}

	private void debugLogHeader(String tag, String mark) {
		if (!IS_DEBUG) return;
		byte[] buff = mBuffer.array();
		int len = mBuffer.position();
		String header = new String(buff, 0, len).replaceAll("\n", mark);
		Log.d(tag, mark + header);
	}

	// -------------------------------------------------------------------------------------------------
	// スレッド専有するなら普通にストリーム使えばいいのでパイプはいらない。
	// } else if (mIsStremingMode) {
	// PipedResponseBodyPartConsumer transportor = new PipedResponseBodyPartConsumer();
	// mResponseBodyStream = new PartPipedInputStream(transportor);
	// endPointConsumer = transportor;
	/*
	 * private static final ByteBuffer NOT_EXIST = ByteBuffer.allocate(0);
	 * private class PipedResponseBodyPartConsumer implements PartProducer, PartConsumer {
	 * ByteBuffer mDelayedBuffer = NOT_EXIST;
	 * ByteBufferWriter mBufferWriter = null;
	 * 
	 * @Override
	 * public synchronized void requestNextPart(ByteBufferWriter bufferWriter) throws IOException {
	 * if (mDelayedBuffer != NOT_EXIST) {
	 * bufferWriter.write(mDelayedBuffer);
	 * mSingleBufferReader.release(mDelayedBuffer);
	 * mDelayedBuffer = NOT_EXIST;
	 * } else {
	 * mBufferWriter = bufferWriter;
	 * }
	 * }
	 * private synchronized void onNextPart() throws IOException {
	 * ByteBuffer buffer = mSingleBufferReader.read();
	 * if (mBufferWriter != null) {
	 * mBufferWriter.write(buffer);
	 * mBufferWriter = null;
	 * mSingleBufferReader.release(mDelayedBuffer);
	 * } else {
	 * mDelayedBuffer = buffer;
	 * }
	 * }
	 * 
	 * @Override
	 * public void postPart(ByteBuffer buffer) {
	 * mSingleBufferReader.setBuffer(buffer);
	 * try {
	 * onNextPart();
	 * } catch (IOException e) {
	 * doError("Response body onPart.", e);
	 * }
	 * }
	 * }
	 */

}
