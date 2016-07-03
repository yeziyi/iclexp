package com.zhidian.wifibox.controller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import android.os.Debug;
import android.os.Environment;
import android.os.RemoteException;
import android.os.StatFs;
import android.text.format.Formatter;
import android.util.Log;

import com.ta.TAApplication;
import com.ta.mvc.command.TACommand;
import com.ta.mvc.common.TARequest;
import com.zhidian.wifibox.activity.CleanMasterActivity;
import com.zhidian.wifibox.data.CleanMasterDataBean;
import com.zhidian.wifibox.data.CleanMasterDataBean.APKBean;
import com.zhidian.wifibox.data.CleanMasterDataBean.BigFileBean;
import com.zhidian.wifibox.data.CleanMasterDataBean.CacheBean;
import com.zhidian.wifibox.data.CleanMasterDataBean.RAMBean;
import com.zhidian.wifibox.data.CleanMasterDataBean.TrashBean;
import com.zhidian.wifibox.root.RootShell;
import com.zhidian.wifibox.util.AppUtils;
import com.zhidian.wifibox.util.FileUtil;
import com.zhidian.wifibox.util.InfoUtil;
import com.zhidian.wifibox.util.Setting;

/**
 * 手机清理逻辑处理
 * 
 * @author xiedezhi
 * 
 */
public class CleanMasterController extends TACommand {
	/**
	 * 扫描
	 */
	public static final String SCAN = "CLEANMASTERCONTROLLER_SCAN";
	/**
	 * 清理
	 */
	public static final String CLEAN = "CLEANMASTERCONTROLLER_CLEAN";

	@Override
	protected void executeCommand() {
		TARequest request = getRequest();
		String command = (String) request.getTag();
		if (command.equals(SCAN)) {
			long t1 = System.currentTimeMillis();
			CountDownLatch cd = new CountDownLatch(3);
			// 缓存扫描
			CacheThread cThread = new CacheThread(this, cd);
			cThread.start();
			// 内存加速
			RAMThread rThread = new RAMThread(this, cd);
			rThread.start();
			// 安装包扫描、残留文件
			TrashThread tThread = new TrashThread(this, cd);
			tThread.start();
			try {
				cd.await();
			} catch (InterruptedException e) {
			}
			sendSuccessMessage(null);
			long t2 = System.currentTimeMillis();
			Log.e("", "scan " + (t2 - t1) + "ms");
		} else if (command.equals(CLEAN)) {
			// 清理
			CleanMasterDataBean bean = (CleanMasterDataBean) request.getData();
			if (bean == null) {
				return;
			}
			CountDownLatch cd = new CountDownLatch(3);
			CacheCleanThread cThread = new CacheCleanThread(this, cd,
					bean.cacheList);
			cThread.start();
			TrashCleanThread tThread = new TrashCleanThread(this, cd,
					bean.getAPKList(), bean.getTrashList(),
					bean.getBigFileList());
			tThread.start();
			RAMCleanThread rThread = new RAMCleanThread(this, cd, bean.ramList);
			rThread.start();
			try {
				cd.await();
			} catch (InterruptedException e) {
			}
			Setting setting = new Setting(TAApplication.getApplication());
			setting.putBoolean(Setting.METER_NEED_CALCULATE, true);
			sendSuccessMessage(null);
		}
	}

	/**
	 * 内存保护白名单
	 */
	private static Set<String> getWhileList() {
		Set<String> ret = new HashSet<String>();
		ret.add(InfoUtil.getCurrentInput(TAApplication.getApplication()));
		Setting setting = new Setting(TAApplication.getApplication());
		String json = setting.getString(Setting.PROTECT_APP);
		JSONArray array = null;
		try {
			array = new JSONArray(json);
		} catch (JSONException e) {
			e.printStackTrace();
		}
		if (array == null) {
			array = new JSONArray();
		}
		for (int i = 0; i < array.length(); i++) {
			try {
				String packname = array.getString(i);
				ret.add(packname);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		return ret;
	}

	private static Set<String> getBlackList() {
		Set<String> ret = new HashSet<String>();
		Setting setting = new Setting(TAApplication.getApplication());
		String json = setting.getString(Setting.BLACK_APP);
		JSONArray array = null;
		try {
			array = new JSONArray(json);
		} catch (JSONException e) {
			e.printStackTrace();
		}
		if (array == null) {
			array = new JSONArray();
		}
		for (int i = 0; i < array.length(); i++) {
			try {
				String packname = array.getString(i);
				ret.add(packname);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		return ret;
	}

	public static class RAMCleanThread extends Thread {
		private CleanMasterController mController;
		private CountDownLatch mCD;
		private List<RAMBean> mList;

		public RAMCleanThread(CleanMasterController controller,
				CountDownLatch cd, List<RAMBean> list) {
			mController = controller;
			mCD = cd;
			mList = list;
			super.setName("RAMCleanThread");
		}

		@Override
		public void run() {
			mController.sendRuntingMessage(new Object[] {
					CleanMasterActivity.MSG_RAM, 0, 0L });
			ActivityManager activityManager = (ActivityManager) TAApplication
					.getApplication()
					.getSystemService(Context.ACTIVITY_SERVICE);
			PackageManager pManager = TAApplication.getApplication()
					.getPackageManager();
			List<PackageInfo> packlist = new ArrayList<PackageInfo>();
			try {
				packlist = pManager.getInstalledPackages(0);
			} catch (Exception e) {
				e.printStackTrace();
			}
			if (mList != null && mList.size() > 0) {
				Set<String> pkgs = new HashSet<String>();
				Map<String, RAMBean> map = new HashMap<String, RAMBean>();
				for (RAMBean bean : mList) {
					if (bean.isSelect) {
						pkgs.add(bean.pkgName);
						map.put(bean.pkgName, bean);
					}
				}
				Set<String> whiteList = getWhileList();
				for (PackageInfo info : packlist) {
					String packageName = info.packageName;
					if (packageName != null
							&& packageName.equals(TAApplication
									.getApplication().getPackageName())) {
						// 过滤掉自己
						continue;
					}
					if (AppUtils.isSystemApp(TAApplication.getApplication(),
							packageName)) {
						continue;
					}
					int state = pManager
							.getApplicationEnabledSetting(packageName);
					if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
							|| state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
						// 过滤被冻结的应用
						continue;
					}
					if (!whiteList.contains(packageName)) {
						pkgs.add(packageName);
					}
				}
				// 杀进程
				if (RootShell.isRootValid()) {
					killApp(pkgs, map);
				} else {
					int count = 0;
					long size = 0;
					for (String pkg : pkgs) {
						RAMBean bean = map.get(pkg);
						if (bean != null) {
							activityManager.killBackgroundProcesses(pkg);
							mController.sendRuntingMessage(bean.name);
							count++;
							size += bean.ram;
							mController.sendRuntingMessage(new Object[] {
									CleanMasterActivity.MSG_RAM, count, size });
						}
					}
				}
			}
			mCD.countDown();
		}

		/**
		 * 杀进程
		 */
		private void killApp(Set<String> pkgs, Map<String, RAMBean> map) {
			createFile();
			try {
				new RootShell.Command(String.format(
						"export LD_LIBRARY_PATH=%s\n",
						System.getenv("LD_LIBRARY_PATH")).replace("$", "\\$"))
						.execute(RootShell.startShell());
				new RootShell.Command("export CLASSPATH="
						+ TAApplication.getApplication().getFilesDir()
								.getAbsolutePath() + "/tk.jar")
						.execute(RootShell.startShell());
			} catch (Exception e) {
				e.printStackTrace();
			}
			int count = 0;
			long size = 0;
			try {
				String cmd = "  ";
				for (String pkg : pkgs) {
					cmd = cmd + "  " + pkg;
					RAMBean bean = map.get(pkg);
					if (bean != null) {
						mController.sendRuntingMessage(bean.name);
						count++;
						size += bean.ram;
					}
				}
				new RootShell.Command(
						"/system/bin/app_process /system/bin com.zhidian.wifibox.root.tk.RootInternal  "
								+ cmd).execute(RootShell.startShell());
			} catch (Exception e) {
				e.printStackTrace();
			}
			mController.sendRuntingMessage(new Object[] {
					CleanMasterActivity.MSG_RAM, count, size });
		}

		/**
		 * 创建jar文件
		 */
		private void createFile() {
			InputStream inputStream = null;
			try {
				inputStream = TAApplication.getApplication().getResources()
						.getAssets().open("zhidian");
				File file = TAApplication.getApplication().getFilesDir();
				if (!file.exists()) {
					file.mkdirs();
				}
				File newFile = new File(file.getAbsolutePath() + "/tk.jar");
				newFile.delete();
				FileOutputStream fileOutputStream = new FileOutputStream(
						newFile.getAbsolutePath());
				byte[] buffer = new byte[1024];
				int count = 0;
				while ((count = inputStream.read(buffer)) > 0) {
					fileOutputStream.write(buffer, 0, count);
				}
				fileOutputStream.flush();
				fileOutputStream.close();
				inputStream.close();
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
			}
		}

	}

	public static class CacheCleanThread extends Thread {
		private CleanMasterController mController;
		private CountDownLatch mCD;
		private List<CacheBean> mList;
		private PackageManager packageManager = TAApplication.getApplication()
				.getPackageManager();
		private int count = 0;
		private long size = 0;

		public CacheCleanThread(CleanMasterController controller,
				CountDownLatch cd, List<CacheBean> list) {
			mController = controller;
			mCD = cd;
			mList = list;
			setName("CacheCleanThread");
		}

		private Method getMethod(String methodName) {
			for (Method method : packageManager.getClass().getMethods()) {
				if (method.getName().equals(methodName))
					return method;
			}
			return null;
		}

		private void invokeMethod(String method, Object... args) {
			try {
				getMethod(method).invoke(packageManager, args);
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		@Override
		public void run() {
			mController.sendRuntingMessage(new Object[] {
					CleanMasterActivity.MSG_CACHE, count, size });
			if (mList != null && mList.size() > 0) {
				boolean select = false;
				long total = 0;
				for (CacheBean bean : mList) {
					mController.sendRuntingMessage(bean.name);
					total = total + bean.cache;
					if (bean.isSelect) {
						select = true;
						count++;
						size += bean.cache;
					}
				}
				if (select) {
					final CountDownLatch countDownLatch = new CountDownLatch(1);
					// 清理
					StatFs stat = new StatFs(Environment.getDataDirectory()
							.getAbsolutePath());
					invokeMethod(
							"freeStorageAndNotify",
							(2 * total)
									+ ((long) stat.getFreeBlocks() * (long) stat
											.getBlockSize()),
							new IPackageDataObserver.Stub() {
								@Override
								public void onRemoveCompleted(
										String packageName, boolean succeeded)
										throws RemoteException {
									mController
											.sendRuntingMessage(new Object[] {
													CleanMasterActivity.MSG_CACHE,
													count, size });
									countDownLatch.countDown();
								}
							});
					try {
						countDownLatch.await();
					} catch (InterruptedException e) {
					}
				}
			}
			mCD.countDown();
		}
	}

	public static class TrashCleanThread extends Thread {
		private CleanMasterController mController;
		private CountDownLatch mCD;
		private List<APKBean> mAPKList;
		private List<TrashBean> mTrashList;
		private List<BigFileBean> mBigFileList;

		public TrashCleanThread(CleanMasterController controller,
				CountDownLatch cd, List<APKBean> alist, List<TrashBean> tlist,
				List<BigFileBean> blist) {
			mController = controller;
			mCD = cd;
			mAPKList = alist;
			mTrashList = tlist;
			mBigFileList = blist;
			setName("TrashCleanThread");
		}

		@Override
		public void run() {
			if (mAPKList != null && mAPKList.size() > 0) {
				int count = 0;
				long size = 0;
				mController.sendRuntingMessage(new Object[] {
						CleanMasterActivity.MSG_APK, count, size });
				for (APKBean bean : mAPKList) {
					if (bean.isSelect) {
						mController.sendRuntingMessage(bean.path);
						File file = new File(bean.path);
						if (file.exists()) {
							file.delete();
						}
						count++;
						size += bean.size;
						mController.sendRuntingMessage(new Object[] {
								CleanMasterActivity.MSG_APK, count, size });
					}
				}
			}
			if (mTrashList != null && mTrashList.size() > 0) {
				int count = 0;
				long size = 0;
				mController.sendRuntingMessage(new Object[] {
						CleanMasterActivity.MSG_TRASH, count, size });
				for (TrashBean bean : mTrashList) {
					if (bean.isSelect) {
						mController.sendRuntingMessage(bean.path);
						File file = new File(bean.path);
						if (file.exists()) {
							file.delete();
						}
						count++;
						size += bean.size;
						mController.sendRuntingMessage(new Object[] {
								CleanMasterActivity.MSG_TRASH, count, size });
					}
				}
			}
			if (mBigFileList != null && mBigFileList.size() > 0) {
				int count = 0;
				long size = 0;
				mController.sendRuntingMessage(new Object[] {
						CleanMasterActivity.MSG_BIG, count, size });
				for (BigFileBean bean : mBigFileList) {
					if (bean.isSelect) {
						mController.sendRuntingMessage(bean.show_path);
						File file = new File(bean.path);
						if (file.exists()) {
							file.delete();
						}
						count++;
						size += bean.size;
						mController.sendRuntingMessage(new Object[] {
								CleanMasterActivity.MSG_BIG, count, size });
					}
				}
			}
			mCD.countDown();
		}
	}

	public static class CacheThread extends Thread {
		private CleanMasterController mController;
		private CountDownLatch mCD;

		private PackageManager packageManager = TAApplication.getApplication()
				.getPackageManager();

		public CacheThread(CleanMasterController controller, CountDownLatch cd) {
			mController = controller;
			mCD = cd;
			super.setName("CacheThread");
		}

		private Method getMethod(String methodName) {
			for (Method method : packageManager.getClass().getMethods()) {
				if (method.getName().equals(methodName))
					return method;
			}
			return null;
		}

		private void invokeMethod(String method, Object... args) {
			try {
				getMethod(method).invoke(packageManager, args);
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		@Override
		public void run() {
			List<ApplicationInfo> packages = packageManager
					.getInstalledApplications(PackageManager.GET_META_DATA);
			final CountDownLatch countDownLatch = new CountDownLatch(
					packages.size());
			final List<CacheBean> clist = new ArrayList<CacheBean>();
			for (ApplicationInfo pkg : packages) {
				invokeMethod("getPackageSizeInfo", pkg.packageName,
						new IPackageStatsObserver.Stub() {

							@Override
							public void onGetStatsCompleted(
									PackageStats pStats, boolean succeeded)
									throws RemoteException {
								if (succeeded) {
									try {
										if (pStats.cacheSize > 0) {
											ApplicationInfo info = packageManager
													.getApplicationInfo(
															pStats.packageName,
															PackageManager.GET_META_DATA);
											CacheBean bean = new CacheBean();
											bean.pkgName = info.packageName;
											bean.name = info
													.loadLabel(packageManager)
													.toString().trim();
											bean.cache = pStats.cacheSize;
											bean.cache_str = Formatter.formatFileSize(
													TAApplication
															.getApplication(),
													bean.cache);
											bean.isSelect = true;
											clist.add(bean);
											List<CacheBean> x = new ArrayList<CacheBean>();
											x.addAll(clist);
											mController.sendRuntingMessage(x);
											mController
													.sendRuntingMessage(bean.name);
										}
									} catch (Exception e) {
										e.printStackTrace();
									}
								}
								countDownLatch.countDown();
							}
						});
			}
			try {
				countDownLatch.await();
			} catch (InterruptedException e) {
			}
			mController.sendRuntingMessage(CleanMasterActivity.MSG_CACHE);
			mCD.countDown();
		}
	}

	public static class RAMThread extends Thread {
		private CleanMasterController mController;
		private CountDownLatch mCD;

		public RAMThread(CleanMasterController controller, CountDownLatch cd) {
			mController = controller;
			mCD = cd;
			super.setName("RAMThread");
		}

		@Override
		public void run() {
			Map<String, RAMBean> map = new HashMap<String, RAMBean>();
			PackageManager pm = TAApplication.getApplication()
					.getPackageManager();
			List<PackageInfo> packageInfos = new ArrayList<PackageInfo>();
			try {
				packageInfos = pm.getInstalledPackages(0);
			} catch (Exception e) {
				e.printStackTrace();
			}
			Set<String> whiteList = getWhileList();
			Set<String> blackList = getBlackList();
			for (PackageInfo info : packageInfos) {
				String packageName = info.packageName;
				if (packageName != null
						&& packageName.equals(TAApplication.getApplication()
								.getPackageName())) {
					// 过滤掉自己
					continue;
				}
				if (packageName != null
						&& packageName
								.equals(InfoUtil.getCurrentInput(TAApplication
										.getApplication()))) {
					// 过滤掉当前输入法
					continue;
				}
				int state = pm.getApplicationEnabledSetting(packageName);
				if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
						|| state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
					// 过滤被冻结的应用
					continue;
				}
				RAMBean bean = new RAMBean();
				bean.pkgName = info.packageName;
				bean.name = info.applicationInfo.loadLabel(pm).toString()
						.trim();
				bean.ram = 0;
				bean.ram_str = Formatter.formatFileSize(
						TAApplication.getApplication(), bean.ram);
				if (whiteList.contains(bean.pkgName)) {
					bean.isSelect = false;
				} else if (AppUtils.isSystemApp(TAApplication.getApplication(),
						info.packageName)) {
					if (blackList.contains(info.packageName)) {
						bean.isSelect = true;
					} else {
						bean.isSelect = false;
					}
				} else {
					bean.isSelect = true;
				}
				map.put(info.packageName, bean);
			}
			ActivityManager activityManager = (ActivityManager) TAApplication
					.getApplication()
					.getSystemService(Context.ACTIVITY_SERVICE);
			List<ActivityManager.RunningAppProcessInfo> list = activityManager
					.getRunningAppProcesses();
			for (ActivityManager.RunningAppProcessInfo info : list) {
				boolean ca = false;
				for (String pkg : info.pkgList) {
					if (map.keySet().contains(pkg)) {
						ca = true;
						break;
					}
				}
				if (ca) {
					int[] myMempid = new int[] { info.pid };
					Debug.MemoryInfo[] memoryInfo = activityManager
							.getProcessMemoryInfo(myMempid);
					long memSize = memoryInfo[0].getTotalPss() * 1024L;
					long aMemSize = memSize / info.pkgList.length;
					for (String pkg : info.pkgList) {
						RAMBean bean = map.get(pkg);
						if (bean != null) {
							bean.ram = bean.ram + aMemSize;
							bean.ram_str = Formatter.formatFileSize(
									TAApplication.getApplication(), bean.ram);
							mController.sendRuntingMessage(bean.name);
						}
					}
					mController.sendRuntingMessage(getRAMList(map));
				}
			}
			mController.sendRuntingMessage(CleanMasterActivity.MSG_RAM);
			mCD.countDown();
		}

		private List<RAMBean> getRAMList(Map<String, RAMBean> map) {
			List<RAMBean> ret = new ArrayList<RAMBean>();
			for (String key : map.keySet()) {
				RAMBean bean = map.get(key);
				if (bean.ram > 0) {
					ret.add(bean);
				}
			}
			return ret;
		}

	}

	public static class Counter {

		private int mCount = 0;

		public synchronized void increase() {
			mCount++;
		}

		public synchronized void reduce() {
			mCount--;
		}

		public synchronized int getCount() {
			return mCount;
		}

	}

	public static class Keeper {
		private int mCount = 0;
		private List<BigFileBean> mBFList = new ArrayList<CleanMasterDataBean.BigFileBean>();
		private List<TrashBean> mTHList = new ArrayList<CleanMasterDataBean.TrashBean>();
		private List<APKBean> mAKList = new ArrayList<CleanMasterDataBean.APKBean>();
		private CleanMasterController mController;

		public Keeper(CleanMasterController controller) {
			mController = controller;
		}

		public synchronized void sendBigFile(BigFileBean bean) {
			mBFList.add(bean);
			if (mBFList.size() % 3 == 0) {
				List<BigFileBean> bigfile = new ArrayList<CleanMasterDataBean.BigFileBean>();
				bigfile.addAll(mBFList);
				mController.sendRuntingMessage(bigfile);
			}
		}

		public synchronized void sendTrashFile(TrashBean bean) {
			mTHList.add(bean);
			if (bean.type == 1) {
				if (mTHList.size() % 20 == 0) {
					List<TrashBean> trash = new ArrayList<CleanMasterDataBean.TrashBean>();
					trash.addAll(mTHList);
					mController.sendRuntingMessage(trash);
				}
			} else {
				if (mTHList.size() % 3 == 0) {
					List<TrashBean> trash = new ArrayList<CleanMasterDataBean.TrashBean>();
					trash.addAll(mTHList);
					mController.sendRuntingMessage(trash);
				}
			}
		}

		public synchronized void sendAPKFile(APKBean bean) {
			mAKList.add(bean);
			if (mAKList.size() % 3 == 0) {
				List<APKBean> apk = new ArrayList<CleanMasterDataBean.APKBean>();
				apk.addAll(mAKList);
				mController.sendRuntingMessage(apk);
			}
		}

		public synchronized void sendAll() {
			List<BigFileBean> bigfile = new ArrayList<CleanMasterDataBean.BigFileBean>();
			bigfile.addAll(mBFList);
			mController.sendRuntingMessage(bigfile);

			List<TrashBean> trash = new ArrayList<CleanMasterDataBean.TrashBean>();
			trash.addAll(mTHList);
			mController.sendRuntingMessage(trash);

			List<APKBean> apk = new ArrayList<CleanMasterDataBean.APKBean>();
			apk.addAll(mAKList);
			mController.sendRuntingMessage(apk);
		}

		public void sendPath(String path) {
			mCount++;
			if (mCount % 10 == 0) {
				for (String sd : FileUtil.sSDPaths) {
					if (path.startsWith(sd)) {
						path = path.replaceFirst(sd, "");
						break;
					}
				}
				mController.sendRuntingMessage(path);
			}
		}
	}

	public static class TrashThread extends Thread {

		private ExecutorService mPool = Executors.newFixedThreadPool(10);
		private CleanMasterController mController;
		private CountDownLatch mCD;
		private Counter mCounter;
		private Keeper mKeeper;
		private int mTime = 0;

		public TrashThread(CleanMasterController controller, CountDownLatch cd) {
			mController = controller;
			mCD = cd;
			mCounter = new Counter();
			mKeeper = new Keeper(mController);
			super.setName("TrashThread");
		}

		@Override
		public void run() {
			// 扫描缩略图
			String directory = Environment.getExternalStorageDirectory()
					.getAbsolutePath() + "/DCIM/.thumbnails";
			File file = new File(directory);
			if (file.exists() && file.isDirectory()) {
				File[] files = file.listFiles();
				if (files != null && files.length > 0) {
					for (File sfile : files) {
						if (sfile != null && sfile.isFile()) {
							TrashBean bean = new TrashBean();
							bean.type = 1;
							bean.path = sfile.getAbsolutePath();
							bean.size = sfile.length();
							bean.isSelect = true;
							mKeeper.sendTrashFile(bean);
						}
					}
				}
			}
			mKeeper.sendAll();
			List<String> paths = FileUtil.sSDPaths;
			if (paths != null && paths.size() > 0) {
				for (String sdPath : paths) {
					if (!mPool.isShutdown()) {
						mCounter.increase();
						mPool.execute(new TrashRunnable(mPool, sdPath,
								mCounter, mKeeper));
					}
				}
			}
			while (true) {
				if (mCounter.getCount() <= 0) {
					mTime++;
				} else {
					mTime = 0;
				}
				if (mTime >= 5) {
					break;
				} else {
					try {
						Thread.sleep(100);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
			mKeeper.sendAll();
			mPool.shutdown();
			mController.sendRuntingMessage(CleanMasterActivity.MSG_APK);
			mController.sendRuntingMessage(CleanMasterActivity.MSG_TRASH);
			mController.sendRuntingMessage(CleanMasterActivity.MSG_BIG);
			mCD.countDown();
		}
	}

	public static class TrashRunnable implements Runnable {

		private ExecutorService mPool;
		private String mPath;
		private Counter mCounter;
		private Keeper mKeeper;

		public TrashRunnable(ExecutorService pool, String path,
				Counter counter, Keeper keeper) {
			mPool = pool;
			mPath = path;
			mCounter = counter;
			mKeeper = keeper;
		}

		@Override
		public void run() {
			mKeeper.sendPath(mPath);
			File file = new File(mPath);
			if (file.isFile()) {
				if (isBigFile(file)) {
					BigFileBean bean = new BigFileBean();
					bean.isSelect = false;
					bean.path = file.getAbsolutePath();
					bean.show_path = bean.path;
					for (String sdp : FileUtil.sSDPaths) {
						if (bean.path.startsWith(sdp)) {
							bean.show_path = bean.path.replaceFirst(sdp, "");
							break;
						}
					}
					bean.size = file.length();
					mKeeper.sendBigFile(bean);
				}
				if (isEmptyFile(file)) {
					// 空白文件
					TrashBean bean = new TrashBean();
					bean.type = 5;
					bean.path = file.getAbsolutePath();
					bean.size = file.length();
					bean.isSelect = true;
					mKeeper.sendTrashFile(bean);
				} else if (isTmpFile(file)) {
					// 临时文件
					TrashBean bean = new TrashBean();
					bean.type = 3;
					bean.path = file.getAbsolutePath();
					bean.size = file.length();
					bean.isSelect = true;
					mKeeper.sendTrashFile(bean);
				} else if (isLogFile(file)) {
					// 日志
					TrashBean bean = new TrashBean();
					bean.type = 4;
					bean.path = file.getAbsolutePath();
					bean.size = file.length();
					bean.isSelect = true;
					mKeeper.sendTrashFile(bean);
				} else if (isAPKFile(file)) {
					// APK
					APKBean bean = new APKBean();
					bean.path = file.getAbsolutePath();
					bean.size = file.length();
					bean.size_str = Formatter.formatFileSize(
							TAApplication.getApplication(), bean.size);
					PackageManager pm = TAApplication.getApplication()
							.getPackageManager();
					PackageInfo packageInfo = pm.getPackageArchiveInfo(
							file.getAbsolutePath(),
							PackageManager.GET_ACTIVITIES);
					if (packageInfo == null) {
						// 损坏
						bean.name = file.getName();
						bean.pkgName = "";
						bean.versionCode = 0;
						bean.damage = true;
						bean.isSelect = true;
					} else {
						bean.name = pm.getApplicationLabel(
								packageInfo.applicationInfo).toString();
						bean.pkgName = packageInfo.packageName;
						bean.versionCode = packageInfo.versionCode;
						bean.damage = false;
						bean.isSelect = true;
						if (AppUtils.isAppExist(TAApplication.getApplication(),
								bean.pkgName)) {
							// 已安装
							int versionCode = AppUtils.getVersionCode(
									TAApplication.getApplication(),
									bean.pkgName);
							if (versionCode < bean.versionCode) {
								// 可升级
								bean.isSelect = true;
							} else {
								bean.isSelect = true;
							}
						} else {
							// 未安装
							bean.isSelect = true;
						}
					}
					mKeeper.sendAPKFile(bean);
				}
			} else if (file.isDirectory()) {
				if (isEmptyDirectory(file)) {
					// 空文件夹
					TrashBean bean = new TrashBean();
					bean.type = 2;
					bean.path = file.getAbsolutePath();
					bean.size = file.length();
					bean.isSelect = true;
					mKeeper.sendTrashFile(bean);
				} else {
					File[] files = file.listFiles();
					if (files != null) {
						for (File sfile : files) {
							if (sfile != null) {
								if (!mPool.isShutdown()) {
									mCounter.increase();
									mPool.execute(new TrashRunnable(mPool,
											sfile.getAbsolutePath(), mCounter,
											mKeeper));
								}
							}
						}
					}
				}
			}
			mCounter.reduce();
		}

	}

	private static boolean isBigFile(File file) {
		if (file == null) {
			return false;
		}
		if (!file.exists()) {
			return false;
		}
		if (!file.isFile()) {
			return false;
		}
		if (file.length() > 10485760L) {
			return true;
		}
		return false;
	}

	/**
	 * 是否临时文件
	 */
	private static boolean isTmpFile(File file) {
		if (file == null) {
			return false;
		}
		if (file.isFile()
				&& file.getAbsolutePath().toLowerCase().endsWith(".tmp")) {
			return true;
		}
		return false;
	}

	/**
	 * 是否日志文件
	 */
	private static boolean isLogFile(File file) {
		if (file == null) {
			return false;
		}
		if (file.isFile()
				&& file.getAbsolutePath().toLowerCase().endsWith(".log")) {
			return true;
		}
		if (file.isFile()
				&& file.getAbsolutePath().toLowerCase().endsWith("log.txt")) {
			return true;
		}
		return false;
	}

	private static boolean isAPKFile(File file) {
		if (file == null) {
			return false;
		}
		if (file.isFile()
				&& file.getAbsolutePath().toLowerCase().endsWith(".apk")) {
			return true;
		}
		return false;
	}

	/**
	 * 是否空文件夹
	 */
	private static boolean isEmptyDirectory(File file) {
		try {
			if (file == null) {
				return false;
			}
			if (file.isDirectory()) {
				if (file.list() == null) {
					return true;
				}
				if (file.list() != null && file.list().length <= 0) {
					return true;
				}
			}
			return false;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	private static boolean isEmptyFile(File file) {
		if (file.exists() && file.isFile()) {
			if (file.length() == 0) {
				return true;
			}
		}
		return false;
	}
}
