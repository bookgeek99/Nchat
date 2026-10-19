package com.nchat.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.nchat.model.Decision;
import com.nchat.model.NotifCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * 通知历史 + 决策记录存储。
 */
public class NchatDb extends SQLiteOpenHelper {

    private static final String NAME = "nchat.db";
    private static final int VERSION = 2;

    private static NchatDb instance;

    public static synchronized NchatDb get(Context ctx) {
        if (instance == null) instance = new NchatDb(ctx.getApplicationContext());
        return instance;
    }

    private NchatDb(Context ctx) {
        super(ctx, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE decisions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "notif_key TEXT,"
                + "pkg TEXT,"
                + "uid_channel TEXT,"
                + "type INTEGER,"
                + "decided_at INTEGER,"
                + "title TEXT,"
                + "body TEXT,"
                + "channel_name TEXT,"
                + "category INTEGER,"
                + "todo_pending INTEGER DEFAULT 0,"
                + "plan_at INTEGER DEFAULT 0"
                + ")");
        db.execSQL("CREATE INDEX idx_decided_at ON decisions(decided_at DESC)");
        db.execSQL("CREATE INDEX idx_notif_key ON decisions(notif_key)");

        // 应用分类记忆：用户手工纠正后记住
        db.execSQL("CREATE TABLE pkg_rules ("
                + "pkg TEXT PRIMARY KEY,"
                + "category INTEGER,"
                + "source INTEGER,"   // 0=用户手工 1=AI
                + "updated_at INTEGER"
                + ")");

        // AI 分类缓存：key = notifKey
        db.execSQL("CREATE TABLE ai_cache ("
                + "notif_key TEXT PRIMARY KEY,"
                + "category INTEGER,"
                + "confidence REAL,"
                + "created_at INTEGER"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            // 待办计划时间（0 = 不计划）
            try {
                db.execSQL("ALTER TABLE decisions ADD COLUMN plan_at INTEGER DEFAULT 0");
            } catch (Throwable ignore) {}
        }
    }

    // ==================== 决策 ====================

    public long insertDecision(Decision d) {
        ContentValues cv = new ContentValues();
        cv.put("notif_key", d.notifKey);
        cv.put("pkg", d.pkg);
        cv.put("uid_channel", d.uidChannel);
        cv.put("type", d.type);
        cv.put("decided_at", d.decidedAt);
        cv.put("title", d.title);
        cv.put("body", d.body);
        cv.put("channel_name", d.channelName);
        cv.put("category", d.category == null ? -1 : d.category.ordinal());
        cv.put("todo_pending", d.todoPending ? 1 : 0);
        cv.put("plan_at", d.planAt);
        long id = getWritableDatabase().insert("decisions", null, cv);
        d.id = id;
        return id;
    }

    public boolean hasDecision(String notifKey) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM decisions WHERE notif_key=? LIMIT 1", new String[]{notifKey})) {
            return c.moveToFirst();
        }
    }

    /** 查历史，type<0 表示全部 */
    public List<Decision> queryHistory(int typeFilter, String keyword, int limit) {
        List<Decision> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM decisions WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (typeFilter > 0) {
            sql.append(" AND type=?");
            args.add(String.valueOf(typeFilter));
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append(" AND (title LIKE ? OR body LIKE ? OR pkg LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        sql.append(" ORDER BY decided_at DESC LIMIT ?");
        args.add(String.valueOf(limit));

        try (Cursor c = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) list.add(readDecision(c));
        }
        return list;
    }

    /** 待办列表：稍后读 + 稍后办，且 pending */
    /**
     * 待办列表。
     * @param now 当前时间；只有 plan_at==0（不计划）或 plan_at<=now 的才返回，
     *            这样「晚上 8 点再提醒」的条目在到点前不会出现在决策流里。
     */
    public List<Decision> queryTodos(long now) {
        List<Decision> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM decisions WHERE type IN (2,3) AND todo_pending=1 "
                        + "AND (plan_at IS NULL OR plan_at=0 OR plan_at<=?) "
                        + "ORDER BY decided_at DESC",
                new String[]{String.valueOf(now)})) {
            while (c.moveToNext()) {
                Decision d = readDecision(c);
                // 同一通知历史上可能积累过重复待办记录（旧版本 bug），这里按 key 去重，
                // 保留最新一条（DESC 排序下第一条即最新）。
                if (d.notifKey != null && !seen.add(d.notifKey)) continue;
                list.add(d);
            }
        }
        // 返回时按计划时间/创建时间升序，最该处理的在前
        java.util.Collections.sort(list, (a, b) -> {
            long pa = a.planAt > 0 ? a.planAt : a.decidedAt;
            long pb = b.planAt > 0 ? b.planAt : b.decidedAt;
            return Long.compare(pa, pb);
        });
        return list;
    }

    /** 兼容旧调用 */
    public List<Decision> queryTodos() {
        return queryTodos(System.currentTimeMillis());
    }

    /** 全部未完成待办（不按计划时间过滤），用于判断某条通知是否已挂着待办 */
    public List<Decision> queryTodosAll() {
        List<Decision> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM decisions WHERE todo_pending=1", null)) {
            while (c.moveToNext()) list.add(readDecision(c));
        }
        return list;
    }

    /**
     * 把一条通知标记为待办。
     * 关键：**同一条通知只保留一条待办记录**。
     *  - 若已存在该 notif_key 的 pending 待办，只更新 type / plan_at，不再插入新行
     *  - 否则插入新行
     * 这修掉了「点一次稍后变两条、再点变三条」的重复累积 bug。
     */
    public void upsertTodo(Decision d) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("type", d.type);
        cv.put("plan_at", d.planAt);
        cv.put("todo_pending", 1);
        cv.put("decided_at", d.decidedAt);
        cv.put("title", d.title);
        cv.put("body", d.body);
        cv.put("channel_name", d.channelName);
        cv.put("category", d.category == null ? -1 : d.category.ordinal());
        cv.put("pkg", d.pkg);
        cv.put("uid_channel", d.uidChannel);
        cv.put("notif_key", d.notifKey);

        // 关键：同一条通知只允许存在一条待办记录。
        // 无论历史上有多少条同 key 的行（旧版本 bug 造成的），这里先全删再插一条，
        // 从根本上保证「点一次稍后 = 一条待办」，不会随点击次数累积。
        db.beginTransaction();
        try {
            db.delete("decisions", "notif_key=? AND type IN (2,3)", new String[]{d.notifKey});
            db.insert("decisions", null, cv);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 某条通知是否在决策历史里存在（用于判断能否用 UPDATE 代替 INSERT） */
    public boolean hasHistory(String notifKey) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM decisions WHERE notif_key=? AND todo_pending=0 LIMIT 1",
                new String[]{notifKey});
        boolean r = c.moveToFirst();
        c.close();
        return r;
    }

    /** 一次性清理旧版本遗留的重复待办（保留每个 key 最新的一条） */
    public int dedupeTodos() {
        SQLiteDatabase db = getWritableDatabase();
        int removed = 0;
        db.beginTransaction();
        try (Cursor c = db.rawQuery(
                "SELECT id, notif_key FROM decisions WHERE type IN (2,3) ORDER BY id DESC", null)) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.List<Long> drop = new java.util.ArrayList<>();
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String k = c.getString(1);
                if (k != null && !seen.add(k)) drop.add(id);
            }
            for (Long id : drop) {
                db.delete("decisions", "id=?", new String[]{String.valueOf(id)});
                removed++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return removed;
    }

    /** 按通知 key 清掉待办标记（待办卡片被处理时用） */
    public void markTodoPendingByKey(String notifKey, int pending) {
        ContentValues cv = new ContentValues();
        cv.put("todo_pending", pending);
        getWritableDatabase().update("decisions", cv,
                "notif_key=? AND todo_pending=1", new String[]{notifKey});
    }

    public void markTodoDone(long id) {
        ContentValues cv = new ContentValues();
        cv.put("todo_pending", 0);
        getWritableDatabase().update("decisions", cv, "id=?", new String[]{String.valueOf(id)});
    }

    /** 按 id 更新一条已有决策（待办条目再次决策时用，避免插入重复行） */
    public void updateDecision(long id, int type, boolean todoPending, long planAt) {
        ContentValues cv = new ContentValues();
        cv.put("type", type);
        cv.put("todo_pending", todoPending ? 1 : 0);
        cv.put("plan_at", planAt);
        cv.put("decided_at", System.currentTimeMillis());
        getWritableDatabase().update("decisions", cv,
                "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteDecision(long id) {
        getWritableDatabase().delete("decisions", "id=?", new String[]{String.valueOf(id)});
    }

    public int countSince(long ts) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM decisions WHERE decided_at>=?", new String[]{String.valueOf(ts)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private Decision readDecision(Cursor c) {
        Decision d = new Decision();
        d.id = c.getLong(c.getColumnIndexOrThrow("id"));
        d.notifKey = c.getString(c.getColumnIndexOrThrow("notif_key"));
        d.pkg = c.getString(c.getColumnIndexOrThrow("pkg"));
        d.uidChannel = c.getString(c.getColumnIndexOrThrow("uid_channel"));
        d.type = c.getInt(c.getColumnIndexOrThrow("type"));
        d.decidedAt = c.getLong(c.getColumnIndexOrThrow("decided_at"));
        d.title = c.getString(c.getColumnIndexOrThrow("title"));
        d.body = c.getString(c.getColumnIndexOrThrow("body"));
        d.channelName = c.getString(c.getColumnIndexOrThrow("channel_name"));
        int cat = c.getInt(c.getColumnIndexOrThrow("category"));
        d.category = cat >= 0 && cat < NotifCategory.values().length
                ? NotifCategory.values()[cat] : NotifCategory.OTHER;
        d.todoPending = c.getInt(c.getColumnIndexOrThrow("todo_pending")) == 1;
        int pIdx = c.getColumnIndex("plan_at");
        d.planAt = pIdx >= 0 ? c.getLong(pIdx) : 0;
        return d;
    }

    // ==================== 应用分类记忆 ====================

    public void setPkgRule(String pkg, NotifCategory cat, boolean fromUser) {
        ContentValues cv = new ContentValues();
        cv.put("pkg", pkg);
        cv.put("category", cat.ordinal());
        cv.put("source", fromUser ? 0 : 1);
        cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("pkg_rules", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 返回 null 表示没有规则 */
    public NotifCategory getPkgRule(String pkg) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT category FROM pkg_rules WHERE pkg=?", new String[]{pkg})) {
            if (c.moveToFirst()) {
                int ord = c.getInt(0);
                if (ord >= 0 && ord < NotifCategory.values().length) {
                    return NotifCategory.values()[ord];
                }
            }
        }
        return null;
    }

    // ==================== AI 缓存 ====================

    public void putAiCache(String notifKey, NotifCategory cat, float conf) {
        ContentValues cv = new ContentValues();
        cv.put("notif_key", notifKey);
        cv.put("category", cat.ordinal());
        cv.put("confidence", conf);
        cv.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("ai_cache", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public NotifCategory getAiCache(String notifKey) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT category FROM ai_cache WHERE notif_key=?", new String[]{notifKey})) {
            if (c.moveToFirst()) {
                int ord = c.getInt(0);
                if (ord >= 0 && ord < NotifCategory.values().length) {
                    return NotifCategory.values()[ord];
                }
            }
        }
        return null;
    }

    public void clearAiCache() {
        getWritableDatabase().delete("ai_cache", null, null);
    }
}