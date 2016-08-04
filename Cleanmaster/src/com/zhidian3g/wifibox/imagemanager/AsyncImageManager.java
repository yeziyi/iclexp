package com.zhidian3g.wifibox.imagemanager;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ThumbnailUtils;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore.Video.Thumbnails;
import android.text.TextUtils;
import android.util.Log;

import com.ta.TAApplication;
import com.zhidian.wifibox.util.AppUtils;
import com.zhidian.wifibox.util.DrawUtil;
import com.zhidian.wifibox.util.FileUtil;

/**
 * 
 * 异步加载图片
 * 
 * @author xiedezhi
 * 
 */
public class AsyncImageManager {

	private final static String TAG = AsyncImageManager.class.getSimpleName();
	/**
	 * 跟线程池有关的变量
	 */
	private static final String THREAD_POOL_MANAGER_NAME = "asyncimagemanager_threadPoolManager";
	private static final int THREAD_POOL_COREPOOL_SIZE = 1;
	private static final int THREAD_POOL_MAXIMUMPOOL_SIZE = 3;
	private static final long THREAD_POOL_KEEPALIVE_TIME = 20;
	private static final int CONNECT_TIME_OUT = 6000;
	private static final int READ_TIME_OUT = 30000;
	private ThreadPoolManager mThreadPoolManager = null;
	/**
	 * 单例
	 */
	private static AsyncImageManager sInstance = null;
	/**
	 * 同步主线程的Handler
	 */
	private Handler mHandler = new Handler(Looper.getMainLooper());
	/**
	 * 图片缓存池
	 */
	private IImageCache mImageCache = null;

	private AsyncImageManager() {
		this(null);
	}

	private AsyncImageManager(IImageCache imageCache) {
		mImageCache = imageCache;
		if (mImageCache == null) {
			mImageCache = new DefaultImageCache();
		}
		ThreadPoolManager.buildInstance(THREAD_POOL_MANAGER_NAME,
				THREAD_POOL_COREPOOL_SIZE, THREAD_POOL_MAXIMUMPOOL_SIZE,
				THREAD_POOL_KEEPALIVE_TIME, TimeUnit.SECONDS, false);
		mThreadPoolManager = ThreadPoolManager
				.getInstance(THREAD_POOL_MANAGER_NAME);
	}

	public static AsyncImageManager getInstance() {
		return buildInstance(null);
	}

	public synchronized static AsyncImageManager buildInstance(
			IImageCache imageCache) {
		if (sInstance == null) {
			sInstance = new AsyncImageManager(imageCache);
		}
		return sInstance;
	}

	/**
	 * 通过图片的URL从内存获取图片的方法
	 */
	public Bitmap loadImgFromMemery(String imgUrl) {
		Bitmap bm = null;
		if (!TextUtils.isEmpty(imgUrl)) {
			bm = mImageCache.get(imgUrl);
		}
		return bm;
	}

	/**
	 * 功能简述:从SD卡加载图片的方法 功能详细描述: 注意:
	 * 
	 * @param imgPath
	 *            图片所在路径
	 * @param imgName
	 *            图片名称
	 * @param imgUrl
	 *            图片URL
	 * @param isCache
	 *            是否添加到内存的缓存里面
	 * @return
	 */
	public Bitmap loadImgFromSD(String imgPath, String imgName, String imgUrl,
			boolean isCache) {
		Bitmap result = null;
		try {
			if (FileUtil.isSDCardAvaiable()) {
				File file = new File(imgPath + imgName);
				if (file.exists()) {
					result = BitmapFactory.decodeFile(imgPath + imgName);
					if (result != null && isCache) {
						mImageCache.set(imgUrl, result);
					}
				}
			}
		} catch (OutOfMemoryError ex) {
			ex.printStackTrace();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return result;
	}

	public Bitmap loadImgFromSD2(String imgPath) {
		Bitmap result = null;
		try {
			File file = new File(imgPath);
			if (file.exists()) {
				BitmapFactory.Options opt = new BitmapFactory.Options();
				// 属性设置为true就可以让解析方法禁止为bitmap分配内存，
				// 返回值也不再是一个Bitmap对象，而是 null。
				// 虽然Bitmap是null了，但是BitmapFactory.Options的outWidth、outHeight和
				// outMimeType属性都会被赋值。
				opt.inJustDecodeBounds = true;
				BitmapFactory.decodeFile(imgPath, opt);

				int reqWidth = 150;
				int reqHeight = 150;
				opt.inSampleSize = calculateInSampleSize(opt, reqWidth,
						reqHeight);
				opt.inJustDecodeBounds = false;
				// 使用获取到的inSampleSize值再次解析图片
				result = BitmapFactory.decodeFile(imgPath, opt);
			}
		} catch (OutOfMemoryError ex) {
			ex.printStackTrace();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return result;
	}

	/**
	 * 根据传入的宽和高，计算出合适的inSampleSize
	 * 
	 * @param options
	 * @param reqWidth
	 * @param reqHeight
	 */
	private int calculateInSampleSize(BitmapFactory.Options options,
			int reqWidth, int reqHeight) {
		// 源图片的高度和宽度
		final int width = options.outWidth;
		final int height = options.outHeight;
		int inSampleSize = 1;
		if (width > reqWidth || height > reqHeight) {
			// 计算出实际宽度和目标宽度的比率
			final int widthRatio = Math.round((float) width / (float) reqWidth);
			final int heightRatio = Math.round((float) height
					/ (float) reqHeight);
			// 选择宽和高中最小的比率作为inSampleSize的值，这样可以保证最终图片的宽和高 
			// 一定都会大于等于目标的宽和高
			inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;
		}

		return inSampleSize;

	}

	public Bitmap loadImgFromVideo(String videoPath, int kind) {
		Bitmap result = null;
		try {
			result = ThumbnailUtils.createVideoThumbnail(videoPath, kind);
		} catch (OutOfMemoryError ex) {
			ex.printStackTrace();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return result;
	}

	/**
	 * 根据URL生成HttpURLConnection的方法
	 */
	public HttpURLConnection createURLConnection(String url) throws Exception {
		HttpURLConnection urlCon = null;
		urlCon = (HttpURLConnection) new URL(url).openConnection();
		urlCon.setConnectTimeout(CONNECT_TIME_OUT);
		urlCon.setReadTimeout(READ_TIME_OUT);
		return urlCon;
	}

	/**
	 * 加载应用图标
	 */
	public Bitmap loadAppIcon(Context ctx, String pkgName) {
		ApplicationInfo info = AppUtils.getAppInfo(ctx, pkgName);
		if (info != null) {
			Drawable drawable = info.loadIcon(ctx.getPackageManager());
			if (drawable != null && drawable instanceof BitmapDrawable) {
				return ((BitmapDrawable) drawable).getBitmap();
			}
		}
		return null;
	}

	/**
	 * 加载apk的图标
	 */
	public Bitmap loadApkIcon(Context context, String packName, String apk_path) {
		try {
			PackageManager pm = context.getPackageManager();
			PackageInfo packageInfo = pm.getPackageArchiveInfo(apk_path,
					PackageManager.GET_ACTIVITIES);
			ApplicationInfo appInfo = packageInfo.applicationInfo;
			if (appInfo != null) {
				appInfo.sourceDir = apk_path;
				appInfo.publicSourceDir = apk_path;
				Drawable apk_icon = appInfo.loadIcon(pm);
				if (apk_icon != null && apk_icon instanceof BitmapDrawable) {
					return ((BitmapDrawable) apk_icon).getBitmap();
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * 从网络加载图片的方法
	 */
	public Bitmap loadImgFromNetwork(String imgUrl) {
		Bitmap result = null;
		InputStream inputStream = null;
		HttpURLConnection urlCon = null;
		try {
			urlCon = createURLConnection(imgUrl);
			inputStream = (InputStream) urlCon.getContent();
			if (inputStream != null) {
				result = BitmapFactory.decodeStream(inputStream);
			}
		} catch (OutOfMemoryError e) {
			e.printStackTrace();
			System.gc();
		} catch (SocketTimeoutException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (inputStream != null) {
				try {
					inputStream.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			if (urlCon != null) {
				urlCon.disconnect();
			}
		}
		return result;
	}

	/**
	 * 功能简述: 加载图片，如果图片在内存里，则直接返回图片，否则异步从SD卡或者网络加载图片 功能详细描述: 注意:
	 * 
	 * @param imgPath
	 *            图片保存的SD卡目录
	 * @param imgName
	 *            图片保存的名称
	 * @param imgUrl
	 *            从网络加载图片的URL
	 * @param isCache
	 *            是否缓存到内存
	 * @param fillet
	 *            是否加上圆角
	 * @param callBack
	 *            回调
	 * @return
	 */
	public Bitmap loadImage(final String imgPath, final String imgName,
			final String imgUrl, final boolean isCache, final boolean fillet,
			final AsyncImageLoadedCallBack callBack) {
		if (imgUrl == null || imgUrl.equals("")) {
			return null;
		}
		Bitmap result = null;
		result = loadImgFromMemery(imgUrl);
		if (result == null) {
			if (mThreadPoolManager == null || mThreadPoolManager.isShutdown()) {
				Log.e("AsyncImageManager",
						"threadPoll == null || threadPoll.isShutdown()");
				return null;
			}
			Runnable loadimg = new Runnable() {
				@Override
				public void run() {
					Bitmap b = null;
					try {
						b = loadImgFromSD(imgPath, imgName, imgUrl, isCache);
						if (b == null) {
							b = loadImgFromNetwork(imgUrl);
							if (b != null) {
								if (fillet) {
									b = DrawUtil.createMaskBitmap(
											TAApplication.getApplication(), b);
								}
								if (b != null) {
									if (FileUtil.isSDCardAvaiable()) {
										FileUtil.saveBitmapToSDFile(b, imgPath
												+ imgName,
												Bitmap.CompressFormat.PNG);
									}
								}
							}
						}
					} catch (OutOfMemoryError error) {
						// 爆内存
						error.printStackTrace();
					}
					if (b != null) {
						if (isCache) {
							mImageCache.set(imgUrl, b);
						}
					}
					// 主线程显示图片
					CallBackRunnable cbRunnable = new CallBackRunnable(b,
							callBack, imgUrl);
					mHandler.post(cbRunnable);

				}
			};
			mThreadPoolManager.execute(loadimg);
		}
		return result;
	}

	public Bitmap loadVideoImage(final String videoPath, final boolean isCache,
			final AsyncImageLoadedCallBack callBack) {
		if (videoPath == null || videoPath.equals("")) {
			return null;
		}
		Bitmap result = null;
		result = loadImgFromMemery(videoPath);
		if (result == null) {
			if (mThreadPoolManager == null || mThreadPoolManager.isShutdown()) {
				Log.e("AsyncImageManager",
						"threadPoll == null || threadPoll.isShutdown()");
				return null;
			}
			Runnable loadimg = new Runnable() {
				@Override
				public void run() {
					Bitmap b = null;
					try {
						b = loadImgFromVideo(videoPath, Thumbnails.MICRO_KIND);
					} catch (OutOfMemoryError error) {
						// 爆内存
						error.printStackTrace();
					}
					if (b != null && isCache) {
						mImageCache.set(videoPath, b);
					}
					// 主线程显示图片
					CallBackRunnable cbRunnable = new CallBackRunnable(b,
							callBack, videoPath);
					mHandler.post(cbRunnable);

				}
			};
			mThreadPoolManager.execute(loadimg);
		}
		return result;
	}

	/**
	 * 功能简述: 加载相册图片，如果图片在内存里，则直接返回图片，否则异步从SD卡或者网络加载图片 功能详细描述: 注意:
	 * 
	 * @param imgPath
	 *            图片保存的SD卡目录
	 * @param imgName
	 *            图片保存的名称
	 * @param imgPath
	 *            从网络加载图片的URL
	 * @param isCache
	 *            是否缓存到内存
	 * @param fillet
	 *            是否加上圆角
	 * @param callBack
	 *            回调
	 * @return
	 */
	public Bitmap loadAlbumImage(final String imgPath, final boolean isCache,
			final AsyncImageLoadedCallBack callBack) {
		if (imgPath == null || imgPath.equals("")) {
			return null;
		}
		Bitmap result = null;
		result = loadImgFromMemery(imgPath);
		if (result == null) {
			if (mThreadPoolManager == null || mThreadPoolManager.isShutdown()) {
				Log.e("AsyncImageManager",
						"threadPoll == null || threadPoll.isShutdown()");
				return null;
			}
			Runnable loadimg = new Runnable() {
				@Override
				public void run() {
					Bitmap b = null;
					try {
						b = loadImgFromSD2(imgPath);
					} catch (OutOfMemoryError error) {
						// 爆内存
						error.printStackTrace();
					}
					if (b != null && isCache) {
						mImageCache.set(imgPath, b);
					}
					// 主线程显示图片
					CallBackRunnable cbRunnable = new CallBackRunnable(b,
							callBack, imgPath);
					mHandler.post(cbRunnable);

				}
			};
			mThreadPoolManager.execute(loadimg);
		}
		return result;
	}

	/**
	 * 加载已安装应用图标
	 * 
	 * @param pkgName
	 *            包名
	 * @param isCache
	 *            是否缓存
	 * @param fillet
	 *            是否加上圆角
	 * @param callBack
	 *            回调
	 * @return
	 */
	public Bitmap loadIcon(final String pkgName, final boolean isCache,
			final boolean fillet, final AsyncImageLoadedCallBack callBack) {
		if (pkgName == null || pkgName.equals("")) {
			return null;
		}
		Bitmap result = null;
		result = loadImgFromMemery(pkgName);
		if (result == null) {
			if (mThreadPoolManager == null || mThreadPoolManager.isShutdown()) {
				Log.e("AsyncImageManager",
						"threadPoll == null || threadPoll.isShutdown()");
				return null;
			}
			Runnable loadimg = new Runnable() {
				@Override
				public void run() {
					Bitmap b = null;
					try {
						b = loadAppIcon(TAApplication.getApplication(), pkgName);
						if (b != null) {
							if (fillet) {
								b = DrawUtil.createMaskBitmap(
										TAApplication.getApplication(), b);
							}
						}
					} catch (OutOfMemoryError error) {
						// 爆内存
						error.printStackTrace();
					}
					if (b != null) {
						if (isCache) {
							mImageCache.set(pkgName, b);
						}
					}
					// 主线程显示图片
					CallBackRunnable cbRunnable = new CallBackRunnable(b,
							callBack, pkgName);
					mHandler.post(cbRunnable);

				}
			};
			mThreadPoolManager.execute(loadimg);
		}
		return result;
	}

	/**
	 * 加载apk安装包的图标
	 * 
	 * @param pkgName
	 *            包名
	 * @param isCache
	 *            是否缓存
	 * @param fillet
	 *            是否加上圆角
	 * @param callBack
	 *            回调
	 * @return
	 */
	public Bitmap loadIcon(final String pkgName, final String apk_path,
			final boolean isCache, final boolean fillet,
			final AsyncImageLoadedCallBack callBack) {
		if (pkgName == null || pkgName.equals("")) {
			return null;
		}
		Bitmap result = null;
		result = loadImgFromMemery(pkgName);
		if (result == null) {
			if (mThreadPoolManager == null || mThreadPoolManager.isShutdown()) {
				Log.e("AsyncImageManager",
						"threadPoll == null || threadPoll.isShutdown()");
				return null;
			}
			Runnable loadimg = new Runnable() {
				@Override
				public void run() {
					Bitmap b = null;
					try {
						b = loadApkIcon(TAApplication.getApplication(),
								pkgName, apk_path);
						if (b != null) {
							if (fillet) {
								b = DrawUtil.createMaskBitmap(
										TAApplication.getApplication(), b);
							}
						}
					} catch (OutOfMemoryError error) {
						// 爆内存
						error.printStackTrace();
					}
					if (b != null) {
						if (isCache) {
							mImageCache.set(pkgName, b);
						}
					}
					// 主线程显示图片
					CallBackRunnable cbRunnable = new CallBackRunnable(b,
							callBack, pkgName);
					mHandler.post(cbRunnable);

				}
			};
			mThreadPoolManager.execute(loadimg);
		}
		return result;
	}

	/**
	 * 取消所有未执行的加载图标任务
	 */
	public void removeAllTask() {
		mThreadPoolManager.removeAllTask();
	}

	/**
	 * 销毁单例
	 */
	public synchronized static void destory() {
		if (sInstance != null) {
			sInstance.clear();
			sInstance = null;
		}
	}

	public void clear() {
		if (mImageCache != null) {
			mImageCache.clear();
		}
	}

	/**
	 * 获取图片后，同步到主线程，回调Runable
	 */
	public static class CallBackRunnable implements Runnable {
		private Bitmap mBitmap = null;
		private AsyncImageLoadedCallBack mCallBack = null;
		private String mImgUrl = null;

		CallBackRunnable(Bitmap img, AsyncImageLoadedCallBack callBack,
				String imgUrl) {
			this.mBitmap = img;
			this.mCallBack = callBack;
			this.mImgUrl = imgUrl;
		}

		@Override
		public void run() {
			if (mCallBack != null) {
				mCallBack.imageLoaded(mBitmap, mImgUrl);
			}
		}
	}

	/**
	 * 获取图片成功后的回调接口
	 */
	public static interface AsyncImageLoadedCallBack {
		public void imageLoaded(Bitmap imageBitmap, String imgUrl);
	}
}
