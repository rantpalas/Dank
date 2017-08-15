package me.saket.dank.utils.glide;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.LibraryGlideModule;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.saket.dank.di.DankAppModule;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

@GlideModule
public class GlideOkHttpProgressModule extends LibraryGlideModule {

  @Override
  public void registerComponents(Context context, Glide glide, Registry registry) {
    OkHttpClient okHttpClient = new OkHttpClient.Builder()
        .connectTimeout(DankAppModule.NETWORK_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DankAppModule.NETWORK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addNetworkInterceptor(createInterceptor(new DispatchingProgressListener()))
        .build();

    registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(okHttpClient));
  }

  private static Interceptor createInterceptor(final ResponseProgressListener listener) {
    return chain -> {
      Request request = chain.request();
      Response response = chain.proceed(request);
      return response.newBuilder()
          .body(new OkHttpProgressResponseBody(request.url(), response.body(), listener))
          .build();
    };
  }

  public interface UiProgressListener {
    void onProgress(long bytesRead, long expectedLength);

    /**
     * Control how often the listener needs an update. 0% and 100% will always be dispatched.
     *
     * @return in percentage (0.2 = call {@link #onProgress} around every 0.2 percent of progress)
     */
    float getGranularityPercentage();
  }

  public static void forget(String url) {
    DispatchingProgressListener.forget(url);
  }

  public static void expect(String url, UiProgressListener listener) {
    DispatchingProgressListener.expect(url, listener);
  }

  private interface ResponseProgressListener {
    void update(HttpUrl url, long bytesRead, long contentLength);
  }

  private static class DispatchingProgressListener implements ResponseProgressListener {
    private static final Map<String, UiProgressListener> LISTENERS = new HashMap<>();
    private static final Map<String, Long> PROGRESSES = new HashMap<>();

    private final Handler handler;

    DispatchingProgressListener() {
      this.handler = new Handler(Looper.getMainLooper());
    }

    static void forget(String url) {
      LISTENERS.remove(url);
      PROGRESSES.remove(url);
    }

    static void expect(String url, UiProgressListener listener) {
      LISTENERS.put(url, listener);
    }

    @Override
    public void update(HttpUrl url, final long bytesRead, final long contentLength) {
      //System.out.printf("%s: %d/%d = %.2f%%%n", url, bytesRead, contentLength, (100f * bytesRead) / contentLength);
      String key = url.toString();
      final UiProgressListener listener = LISTENERS.get(key);
      if (listener == null) {
        return;
      }
      if (contentLength <= bytesRead) {
        forget(key);
      }
      if (needsDispatch(key, bytesRead, contentLength, listener.getGranularityPercentage())) {
        handler.post(() -> listener.onProgress(bytesRead, contentLength));
      }
    }

    private boolean needsDispatch(String key, long current, long total, float granularity) {
      if (granularity == 0 || current == 0 || total == current) {
        return true;
      }
      float percent = 100f * current / total;
      long currentProgress = (long) (percent / granularity);
      Long lastProgress = PROGRESSES.get(key);
      if (lastProgress == null || currentProgress != lastProgress) {
        PROGRESSES.put(key, currentProgress);
        return true;
      } else {
        return false;
      }
    }
  }

  private static class OkHttpProgressResponseBody extends ResponseBody {
    private final HttpUrl url;
    private final ResponseBody responseBody;
    private final ResponseProgressListener progressListener;
    private BufferedSource bufferedSource;

    OkHttpProgressResponseBody(HttpUrl url, ResponseBody responseBody, ResponseProgressListener progressListener) {
      this.url = url;
      this.responseBody = responseBody;
      this.progressListener = progressListener;
    }

    @Override
    public MediaType contentType() {
      return responseBody.contentType();
    }

    @Override
    public long contentLength() {
      return responseBody.contentLength();
    }

    @Override
    public BufferedSource source() {
      if (bufferedSource == null) {
        bufferedSource = Okio.buffer(source(responseBody.source()));
      }
      return bufferedSource;
    }

    private Source source(Source source) {
      return new ForwardingSource(source) {
        long totalBytesRead = 0L;

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
          long bytesRead = super.read(sink, byteCount);
          long fullLength = responseBody.contentLength();
          if (bytesRead == -1) { // this source is exhausted
            totalBytesRead = fullLength;
          } else {
            totalBytesRead += bytesRead;
          }
          progressListener.update(url, totalBytesRead, fullLength);
          return bytesRead;
        }
      };
    }
  }
}