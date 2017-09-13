package com.example.cjy.downloadlibrary;

import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by CJY on 2017/8/22.
 */

public class DownloadManager {
    public static final String TAG = DownloadManager.class.getSimpleName();
    public static final int CPU_THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    public static final int ALL_THREAD_COUNT = CPU_THREAD_COUNT * 2 + 1;
    public static final long KEEP_ALIVE_TIME = 60L;
    public static final BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(128);

    private static ExecutorService singleThreadPool = Executors.newSingleThreadExecutor();
    private static ExecutorService downloadThreadPool = new ThreadPoolExecutor(CPU_THREAD_COUNT,
            ALL_THREAD_COUNT, KEEP_ALIVE_TIME, TimeUnit.SECONDS, workQueue);

    private static OkHttpClient sOkHttpClient;
    private long contentLength;     //下载文件长度
    private long fileLength;        //本地文件长度
    private boolean isCompleted;   //传输文件是否完成
    private String filePath;
    private int blockSize;

    //单例模式
    private static class SingletonHolder {
        private static final DownloadManager INSTANCE = new DownloadManager();
    }

    private  DownloadManager() {
        OkHttpClient.Builder okBuilder = new OkHttpClient.Builder()
                .connectTimeout(30000, TimeUnit.MILLISECONDS)
                .readTimeout(20000, TimeUnit.MILLISECONDS)
                .readTimeout(20000, TimeUnit.MILLISECONDS);
//                .addInterceptor(new HttpLoggingInterceptor()
//                        .setLevel(HttpLoggingInterceptor.Level.BODY));
        sOkHttpClient = okBuilder.build();
    }

    public static DownloadManager getInstance() {
        return SingletonHolder.INSTANCE;
    }

    public void downloadFile(final int  downloadCode, final String url, final Handler handler) {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                //检测下载文件长度
                contentLength = getContentLength(url);
                Log.i("TAG", "下载文件长度为" + contentLength + "b");
                if(contentLength < 0) {
                    Log.i(TAG, "读取文件失败！");
                    return;
                }
                String path = Environment.getExternalStorageDirectory() + "/MutiDownlaod2/";
                File file = new File(path);
                if(!file.exists()) {
                    file.mkdir();
                }
                String fileName = getFilename(url);
                filePath = path + fileName;
                Log.i(TAG, "download file path:" + filePath);
                blockSize = (int) (contentLength % CPU_THREAD_COUNT == 0
                                        ? contentLength / CPU_THREAD_COUNT
                                        : contentLength / CPU_THREAD_COUNT + 1);
                file = new File(filePath);

                if(file.exists() && file.length() > 0) {
                    fileLength = file.length();
                    int n = 1;
                    while (fileLength >= contentLength) {
                        int dotIndex = fileName.lastIndexOf(".");
                        String fileNameOther;
                        if(dotIndex == -1) {
                            fileNameOther = fileName + "(" + n + ")";
                        } else {
                            fileNameOther = fileName.substring(0, dotIndex) + "(" + n + ")" + fileName.substring(dotIndex);
                        }
                        File newFile = new File(path + fileNameOther);
                        file = newFile;
                        fileLength = newFile.length();
                        n++;
                    }
                }


                DownloadRunnable[]  downloadRunnables = new DownloadRunnable[CPU_THREAD_COUNT];
                for(int i = 0; i < CPU_THREAD_COUNT; i++) {
                    downloadRunnables[i] = new DownloadRunnable(sOkHttpClient, url, file, blockSize, (i+1));
                    if(downloadThreadPool != null) {
                        Log.i(TAG, "开始下载线程：" + downloadRunnables[i]);
                        downloadThreadPool.execute(downloadRunnables[i]);
                    }
                }
                boolean isFinished = false;
                int downloadAllSize = 0;
                while(!isFinished) {
                    isFinished = true;
                    //当前所有线程下载总量
                    downloadAllSize = 0;
                    for (int i = 0; i < downloadRunnables.length; i++) {
                        downloadAllSize += downloadRunnables[i].getDownloadLength();
//                        Log.i(TAG, "每时刻下载长度:" + downloadAllSize);
                        if(!downloadRunnables[i].isCompleted()) {
                            isFinished = false;
                        }
                    }
                    Log.i(TAG, "此刻下载的长度为" + downloadAllSize);

                    Message msg = Message.obtain();
                    msg.what = downloadCode;
                    msg.getData().putInt("currentProgress", downloadAllSize);
                    msg.getData().putInt("maxProgress", (int) contentLength);
                    handler.sendMessage(msg);
                    SystemClock.sleep(1000);
                }
            }
        };
        singleThreadPool.execute(runnable);
    }

    /**
     * 获取下载长度
     * @return
     */
    private long getContentLength(String url) {
        Request request = new Request.Builder()
                .url(url)
                .build();
        try {
            Call call = sOkHttpClient.newCall(request);
            Response response = call.execute();
            if(response != null && response.isSuccessful()) {
                long contentLength = response.body().contentLength();
                response.close();
                return contentLength == 0 ? DownloadInfo.FILE_ERROR : contentLength;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return DownloadInfo.FILE_ERROR;
    }

    /**
     * 获取文件名字
     *
     * @param url   url地址
     * @return
     */
    private String getFilename(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            String filename = url != null ?
                    url.substring(url.lastIndexOf("/") + 1) : null;
            if (filename == null || "".equals(filename.trim())) {//如果获取不到文件名称
                for (int i = 0; ; i++) {
                    String mine = connection.getHeaderField(i);
                    if (mine == null) break;
                    if ("content-disposition".equals(connection.getHeaderFieldKey(i).toLowerCase())) {
                        Matcher m = Pattern.compile(".*filename=(.*)").
                                matcher(mine.toLowerCase());
                        if (m.find()) return m.group(1);
                    }
                }
                filename = UUID.randomUUID() + ".tmp";//默认取一个文件名
            }
            return filename;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
