package com.liulishuo.filedownloader.services;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import com.liulishuo.filedownloader.model.FileDownloadModel;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.liulishuo.filedownloader.util.FileDownloadHelper;
import com.liulishuo.filedownloader.util.FileDownloadLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by Jacksgong on 9/24/15.
 */
class FileDownloadDBHelper implements IFileDownloadDBHelper {

    private FileDownloadDBOpenHelper openHelper;
    private SQLiteDatabase db;

    public final static String TABLE_NAME = "filedownloader";

    private Map<Integer, FileDownloadModel> downloaderModelMap = new HashMap<>();

    public FileDownloadDBHelper() {
        openHelper = new FileDownloadDBOpenHelper(FileDownloadHelper.getAppContext());

        db = openHelper.getWritableDatabase();

        refreshDataFromDB();
    }


    @Override
    public Set<FileDownloadModel> getAllUnComplete() {
        return null;
    }

    @Override
    public Set<FileDownloadModel> getAllCompleted() {
        return null;
    }

    @Override
    public void refreshDataFromDB() {
        // TODO 优化，分段加载，数据多了以后
        // TODO 自动清理一个月前的数据
        Cursor c = db.rawQuery("SELECT * FROM " + TABLE_NAME, null);

        List<Integer> dirtyList = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                FileDownloadModel model = new FileDownloadModel();
                model.setId(c.getInt(c.getColumnIndex(FileDownloadModel.ID)));
                model.setUrl(c.getString(c.getColumnIndex(FileDownloadModel.URL)));
                model.setPath(c.getString(c.getColumnIndex(FileDownloadModel.PATH)));
                model.setNeedNotification(c.getInt(c.getColumnIndex(FileDownloadModel.NEED_NOTIFICATION)) != 0);
                model.setTitle(c.getString(c.getColumnIndex(FileDownloadModel.TITLE)));
                model.setDesc(c.getString(c.getColumnIndex(FileDownloadModel.DESC)));
                model.setProgressNotifyNums(c.getInt(c.getColumnIndex(FileDownloadModel.PROGRESS_NOTIFY_NUMS)));
                model.setStatus(c.getInt(c.getColumnIndex(FileDownloadModel.STATUS)));
                model.setSoFar(c.getInt(c.getColumnIndex(FileDownloadModel.SOFAR)));
                model.setTotal(c.getInt(c.getColumnIndex(FileDownloadModel.TOTAL)));
                model.setErrMsg(c.getString(c.getColumnIndex(FileDownloadModel.ERR_MSG)));
                model.seteTag(c.getString(c.getColumnIndex(FileDownloadModel.ETAG)));

                if (model.getStatus() == FileDownloadStatus.pending) {
                    //脏数据 在数据库中是pending或是progress，说明是之前
                    dirtyList.add(model.getId());
                } else if (model.getStatus() == FileDownloadStatus.progress) {
                    // 保证断点续传可以覆盖到
                    model.setStatus(FileDownloadStatus.paused);
                }
                downloaderModelMap.put(model.getId(), model);
            }
        } finally {
            c.close();

            for (Integer integer : dirtyList) {
                downloaderModelMap.remove(integer);
            }

            // db
            if (dirtyList.size() > 0) {
                String args = TextUtils.join(", ", dirtyList);
                FileDownloadLog.d(this, "delete %s", args);
                db.execSQL(String.format("DELETE FROM %s WHERE %s IN (%s);", TABLE_NAME, FileDownloadModel.ID, args));
            }

        }

    }

    @Override
    public FileDownloadModel find(final int id) {
        return downloaderModelMap.get(id);
    }

    @Override
    public void insert(FileDownloadModel downloadModel) {
        downloaderModelMap.put(downloadModel.getId(), downloadModel);

        // db
        db.insert(TABLE_NAME, null, downloadModel.toContentValues());
    }

    @Override
    public void update(FileDownloadModel downloadModel) {
        if (downloadModel == null) {
            FileDownloadLog.d(this, "update but model == null!");
            return;
        }

        if (find(downloadModel.getId()) != null) {
            // db
            ContentValues cv = downloadModel.toContentValues();
            db.update(TABLE_NAME, cv, FileDownloadModel.ID + " = ? ", new String[]{String.valueOf(downloadModel.getId())});
        } else {
            insert(downloadModel);
        }
    }

    @Override
    public void remove(int id) {
        downloaderModelMap.remove(id);

        // db
        db.delete(TABLE_NAME, FileDownloadModel.ID + " = ?", new String[]{String.valueOf(id)});
    }

    private long lastRefreshUpdate = 0;

    private final int MIN_REFRESH_DURATION_2_DB = 10;

    @Override
    public void update(int id, int status, int soFar, int total) {
        final FileDownloadModel downloadModel = find(id);
        if (downloadModel != null) {
            downloadModel.setStatus(status);
            downloadModel.setSoFar(soFar);
            downloadModel.setTotal(total);

            boolean needRefresh2DB = false;
            if (System.currentTimeMillis() - lastRefreshUpdate > MIN_REFRESH_DURATION_2_DB) {
                needRefresh2DB = true;
                lastRefreshUpdate = System.currentTimeMillis();
            }

            if (!needRefresh2DB) {
                return;
            }

            // db
            ContentValues cv = new ContentValues();
            cv.put(FileDownloadModel.STATUS, status);
            cv.put(FileDownloadModel.SOFAR, soFar);
            cv.put(FileDownloadModel.TOTAL, total);
            db.update(TABLE_NAME, cv, FileDownloadModel.ID + " = ? ", new String[]{String.valueOf(id)});
        }

    }

    @Override
    public void updateHeader(int id, String etag) {
        final FileDownloadModel downloadModel = find(id);
        if (downloadModel != null) {
            downloadModel.seteTag(etag);

            //db
            ContentValues cv = new ContentValues();
            cv.put(FileDownloadModel.ETAG, etag);
            db.update(TABLE_NAME, cv, FileDownloadModel.ID + " = ? ", new String[]{String.valueOf(id)});
        }
    }

    @Override
    public void updateError(int id, String errMsg) {
        final FileDownloadModel downloadModel = find(id);
        if (downloadModel != null) {
            downloadModel.setStatus(FileDownloadStatus.error);
            downloadModel.setErrMsg(errMsg);

            // db
            ContentValues cv = new ContentValues();
            cv.put(FileDownloadModel.ERR_MSG, errMsg);
            cv.put(FileDownloadModel.STATUS, FileDownloadStatus.error);
            db.update(TABLE_NAME, cv, FileDownloadModel.ID + " = ? ", new String[]{String.valueOf(id)});
        }
    }

    @Override
    public void updateComplete(int id, final int total) {
        final FileDownloadModel downloadModel = find(id);
        if (downloadModel != null) {
            downloadModel.setStatus(FileDownloadStatus.completed);
            downloadModel.setSoFar(total);
            downloadModel.setTotal(total);
        }

        //db
        ContentValues cv = new ContentValues();
        cv.put(FileDownloadModel.STATUS, FileDownloadStatus.completed);
        cv.put(FileDownloadModel.TOTAL, total);
        cv.put(FileDownloadModel.SOFAR, total);
        db.update(TABLE_NAME, cv, FileDownloadModel.ID + " = ? ", new String[]{String.valueOf(id)});
    }

    @Override
    public void updatePause(int id) {
        final FileDownloadModel downloadModel = find(id);
        if (downloadModel != null) {
            downloadModel.setStatus(FileDownloadStatus.paused);

            // db
            ContentValues cv = new ContentValues();
            cv.put(FileDownloadModel.STATUS, FileDownloadStatus.paused);
            db.update(TABLE_NAME, cv, FileDownloadModel.ID + " = ? ", new String[]{String.valueOf(id)});
        }
    }

    @Override
    public void updatePending(int id) {
        final FileDownloadModel downloadModel = find(id);
        if (downloadModel != null) {
            downloadModel.setStatus(FileDownloadStatus.pending);

            // db
            ContentValues cv = new ContentValues();
            cv.put(FileDownloadModel.STATUS, FileDownloadStatus.pending);
            db.update(TABLE_NAME, cv, FileDownloadModel.ID + " = ? ", new String[]{String.valueOf(id)});
        }
    }
}