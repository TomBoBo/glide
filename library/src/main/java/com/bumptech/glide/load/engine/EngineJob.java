package com.bumptech.glide.load.engine;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.bumptech.glide.load.Key;
import com.bumptech.glide.request.ResourceCallback;
import com.bumptech.glide.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * A class that manages a load by adding and removing callbacks for for the load and notifying
 * callbacks when the load completes.
 */
class EngineJob<R> implements DecodeJob.Callback<R> {
  private static final EngineResourceFactory DEFAULT_FACTORY = new EngineResourceFactory();
  private static final Handler MAIN_THREAD_HANDLER =
      new Handler(Looper.getMainLooper(), new MainThreadCallback());

  private static final int MSG_COMPLETE = 1;
  private static final int MSG_EXCEPTION = 2;

  private final List<ResourceCallback> cbs = new ArrayList<>();
  private final EngineResourceFactory engineResourceFactory;
  private final EngineJobListener listener;
  private final Key key;
  private final ExecutorService diskCacheService;
  private final ExecutorService sourceService;
  private final boolean isCacheable;

  private boolean isCancelled;
  // Either resource or exception (particularly exception) may be returned to us null, so use
  // booleans to track if
  // we've received them instead of relying on them to be non-null. See issue #180.
  private Resource<?> resource;
  private boolean hasResource;
  private boolean hasLoadFailed;
  // A put of callbacks that are removed while we're notifying other callbacks of a change in
  // status.
  private List<ResourceCallback> ignoredCallbacks;
  private EngineResource<?> engineResource;

  private volatile Future<?> future;
  private DecodeJob<R> decodeJob;

  public EngineJob(Key key, ExecutorService diskCacheService, ExecutorService sourceService,
      boolean isCacheable, EngineJobListener listener) {
    this(key, diskCacheService, sourceService, isCacheable, listener, DEFAULT_FACTORY);
  }

  public EngineJob(Key key, ExecutorService diskCacheService, ExecutorService sourceService,
      boolean isCacheable, EngineJobListener listener,
      EngineResourceFactory engineResourceFactory) {
    this.key = key;
    this.diskCacheService = diskCacheService;
    this.sourceService = sourceService;
    this.isCacheable = isCacheable;
    this.listener = listener;
    this.engineResourceFactory = engineResourceFactory;
  }

  public void start(DecodeJob<R> decodeJob) {
    this.decodeJob = decodeJob;
    future = diskCacheService.submit(decodeJob);
  }

  public void addCallback(ResourceCallback cb) {
    Util.assertMainThread();
    if (hasResource) {
      cb.onResourceReady(engineResource);
    } else if (hasLoadFailed) {
      cb.onLoadFailed();
    } else {
      cbs.add(cb);
    }
  }

  public void removeCallback(ResourceCallback cb) {
    Util.assertMainThread();
    if (hasResource || hasLoadFailed) {
      addIgnoredCallback(cb);
    } else {
      cbs.remove(cb);
      if (cbs.isEmpty()) {
        cancel();
      }
    }
  }

  // We cannot remove callbacks while notifying our list of callbacks directly because doing so
  // would cause a ConcurrentModificationException. However, we need to obey the cancellation
  // request such that if notifying a callback early in the callbacks list cancels a callback later
  // in the request list, the cancellation for the later request is still obeyed. Using a put of
  // ignored callbacks allows us to avoid the exception while still meeting the requirement.
  private void addIgnoredCallback(ResourceCallback cb) {
    if (ignoredCallbacks == null) {
      ignoredCallbacks = new ArrayList<>(2);
    }
    if (!ignoredCallbacks.contains(cb)) {
      ignoredCallbacks.add(cb);
    }
  }

  private boolean isInIgnoredCallbacks(ResourceCallback cb) {
    return ignoredCallbacks != null && ignoredCallbacks.contains(cb);
  }

  // Exposed for testing.
  void cancel() {
    if (hasLoadFailed || hasResource || isCancelled) {
      return;
    }

    decodeJob.cancel();
    Future currentFuture = future;
    if (currentFuture != null) {
      currentFuture.cancel(true);
    }
    isCancelled = true;
    listener.onEngineJobCancelled(this, key);
  }

  // Exposed for testing.
  boolean isCancelled() {
    return isCancelled;
  }

  private void handleResultOnMainThread() {
    if (isCancelled) {
      resource.recycle();
      return;
    } else if (cbs.isEmpty()) {
      throw new IllegalStateException("Received a resource without any callbacks to notify");
    }
    engineResource = engineResourceFactory.build(resource, isCacheable);
    hasResource = true;

    // Hold on to resource for duration of request so we don't recycle it in the middle of
    // notifying if it synchronously released by one of the callbacks.
    engineResource.acquire();
    listener.onEngineJobComplete(key, engineResource);

    for (ResourceCallback cb : cbs) {
      if (!isInIgnoredCallbacks(cb)) {
        engineResource.acquire();
        cb.onResourceReady(engineResource);
      }
    }
    // Our request is complete, so we can release the resource.
    engineResource.release();
  }

  @Override
  public void onResourceReady(Resource<R> resource) {
    this.resource = resource;
    MAIN_THREAD_HANDLER.obtainMessage(MSG_COMPLETE, this).sendToTarget();
  }

  @Override
  public void onLoadFailed() {
    MAIN_THREAD_HANDLER.obtainMessage(MSG_EXCEPTION, this).sendToTarget();
  }

  @Override
  public void reschedule(DecodeJob<?> job) {
    // TODO: what if this is fetched from cache?
    future = sourceService.submit(job);
  }

  private void handleExceptionOnMainThread() {
    if (isCancelled) {
      return;
    } else if (cbs.isEmpty()) {
      throw new IllegalStateException("Received an exception without any callbacks to notify");
    }
    hasLoadFailed = true;

    listener.onEngineJobComplete(key, null);

    for (ResourceCallback cb : cbs) {
      if (!isInIgnoredCallbacks(cb)) {
        cb.onLoadFailed();
      }
    }
  }

  // Visible for testing.
  static class EngineResourceFactory {
    public <R> EngineResource<R> build(Resource<R> resource, boolean isMemoryCacheable) {
      return new EngineResource<>(resource, isMemoryCacheable);
    }
  }

  private static class MainThreadCallback implements Handler.Callback {

    @Override
    public boolean handleMessage(Message message) {
      if (MSG_COMPLETE == message.what || MSG_EXCEPTION == message.what) {
        EngineJob job = (EngineJob) message.obj;
        if (MSG_COMPLETE == message.what) {
          job.handleResultOnMainThread();
        } else {
          job.handleExceptionOnMainThread();
        }
        return true;
      }

      return false;
    }
  }
}
