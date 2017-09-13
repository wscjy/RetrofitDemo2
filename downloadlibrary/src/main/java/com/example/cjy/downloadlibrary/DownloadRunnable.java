package com.example.cjy.downloadlibrary;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by CJY on 2017/8/22.
 */

public class DownloadRunnable implements Runnable {
    public static final String TAG = DownloadRunnable.class.getSimpleName();
    //当前下载是否完成
    private boolean isCompleted = false;
    //当前下载文件长度
    private long downloadLength = 0;
    //文件保存路径
    private File file;
    //OkHttp
    private OkHttpClient mOkHttpClient;
    //url
    private String url;
    //当前下载线程ID
    private int threadId;
    //线程下载数据长度
    private int blockSize;


    public  DownloadRunnable(OkHttpClient okHttpClient, String url, File file, int blockSize, int threadId) {
        this.mOkHttpClient = okHttpClient;
        this.url = url;
        this.file = file;
        this.blockSize = blockSize;
        this.threadId = threadId;
    }

    //线程文件是否下载完毕
    public boolean isCompleted() {
        return isCompleted;
    }

    //线程下载文件长度
    public long getDownloadLength() {
        return downloadLength;
    }

    @Override
    public void run() {
        BufferedInputStream bis = null;
        RandomAccessFile raf = null;
        try {
            int startPos = blockSize * (threadId - 1);
            int endPos = blockSize * threadId - 1;
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("RANGE", "bytes=" + startPos + "-" + endPos)
                    //加上这句是避免okhttp长链接
//                    .addHeader("Connection", "close")
                    .build();
            System.out.println(Thread.currentThread().getName() + " bytes=" + startPos + "-" + endPos);
            Call call = mOkHttpClient.newCall(request);
            Response response = call.execute();
            InputStream is = response.body().byteStream();
            byte[] buffer = new byte[1024];
            bis = new BufferedInputStream(is);
            raf = new RandomAccessFile(file, "rw");
            raf.seek(startPos);
            int len;
            while((len = bis.read(buffer, 0, 1024)) != -1) {
//                Log.i(TAG, "传输长度为" + len + "k");
                raf.write(buffer, 0, len);
                downloadLength += len;
            }
            isCompleted = true;
            Log.i(TAG, "current thread task has finished,all size:" + downloadLength);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(bis != null) {
                try {
                    bis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
