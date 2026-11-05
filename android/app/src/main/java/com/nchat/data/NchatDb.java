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
    private static final int VERSION = 5;

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
                + "plan_at INTEGER DEFAULT 0,"
                // v3 追踪字段：记录通知来源与跳转结果
                + "intent_pkg TEXT,"
                + "intent_target TEXT,"
                + "intent_action TEXT,"
                + "intent_data TEXT,"
                + "notif_tag TEXT,"
                + "notif_id INTEGER DEFAULT 0,"
                + "notif_flags INTEGER DEFAULT 0,"
                + "notif_category TEXT,"
                + "notif_channel TEXT,"
                + "notif_group TEXT,"
                + "is_group_summary INTEGER DEFAULT 0,"
                + "post_time INTEGER DEFAULT 0,"
                + "jump_result INTEGER DEFAULT -1,"
                + "jump_cost_ms INTEGER DEFAULT -1,"
                // v4：跳转失败原因（排查「点了没反应」用）
                + "jump_reason TEXT,"
                + "jump_top TEXT"
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
        // v5：已屏蔽的渠道。
        // 之前屏蔽只调 daemon，本地一点记录都没有，
        // 导致统计页没法区分「已经屏蔽了」和「还在烦我」，
        // 也没法在重启后恢复 UI 状态。
        db.execSQL("CREATE TABLE muted_channels ("
                + "uid_channel TEXT PRIMARY KEY,"
                + "pkg TEXT,"
                + "channel_id TEXT,"
                + "channel_name TEXT,"
                + "muted_at INTEGER"
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
        if (oldV < 3) {
            // v3：通知来源追踪字段。逐条 ALTER，重复列名抛异常直接忽略。
            String[] alters = new String[]{
                    "ALTER TABLE decisions ADD COLUMN intent_pkg TEXT",
                    "ALTER TABLE decisions ADD COLUMN intent_target TEXT",
                    "ALTER TABLE decisions ADD COLUMN intent_action TEXT",
                    "ALTER TABLE decisions ADD COLUMN intent_data TEXT",
                    "ALTER TABLE decisions ADD COLUMN notif_tag TEXT",
                    "ALTER TABLE decisions ADD COLUMN notif_id INTEGER DEFAULT 0",
                    "ALTER TABLE decisions ADD COLUMN notif_flags INTEGER DEFAULT 0",
                    "ALTER TABLE decisions ADD COLUMN notif_category TEXT",
                    "ALTER TABLE decisions ADD COLUMN notif_channel TEXT",
                    "ALTER TABLE decisions ADD COLUMN notif_group TEXT",
                    "ALTER TABLE decisions ADD COLUMN is_group_summary INTEGER DEFAULT 0",
                    "ALTER TABLE decisions ADD COLUMN post_time INTEGER DEFAULT 0",
                    "ALTER TABLE decisions ADD COLUMN jump_result INTEGER DEFAULT -1",
                    "ALTER TABLE decisions ADD COLUMN jump_cost_ms INTEGER DEFAULT -1"
            };
            for (String sql : alters) {
                try {
                    db.execSQL(sql);
                } catch (Throwable ignore) {}
            }
        }
        if (oldV < 4) {
            // v4：jump reason / top，用来诊断「点了立即阅读打不开」
            String[] alters = new String[]{
                    "ALTER TABLE decisions ADD COLUMN jump_reason TEXT",
                    "ALTER TABLE decisions ADD COLUMN jump_top TEXT"
            };
            for (String sql : alters) {
                try {
                    db.execSQL(sql);
                } catch (Throwable ignore) {}
            }
        }
        if (oldV < 5) {
            // v5：已屏蔽渠道记录
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS muted_channels ("
                        + "uid_channel TEXT PRIMARY KEY,"
                        + "pkg TEXT,"
                        + "channel_id TEXT,"
                        + "channel_name TEXT,"
                        + "muted_at INTEGER"
                        + ")");
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
        putTrace(cv, d);
        long id = getWritableDatabase().insert("decisions", null, cv);
        d.id = id;
        return id;
    }

    /** v3 追踪字段统一写入，insert / upsert / update 三处共用 */
    private static void putTrace(ContentValues cv, Decision d) {
        cv.put("intent_pkg", d.intentPkg);
        cv.put("intent_target", d.intentTarget);
        cv.put("intent_action", d.intentAction);
        cv.put("intent_data", d.intentData);
        cv.put("notif_tag", d.notifTag);
        cv.put("notif_id", d.notifId);
        cv.put("notif_flags", d.notifFlags);
        cv.put("notif_category", d.notifCategory);
        cv.put("notif_channel", d.notifChannel);
        cv.put("notif_group", d.notifGroup);
        cv.put("is_group_summary", d.isGroupSummary ? 1 : 0);
        cv.put("post_time", d.postTime);
        cv.put("jump_result", d.jumpResult);
        cv.put("jump_cost_ms", d.jumpCostMs);
        cv.put("jump_reason", d.jumpReason);
        cv.put("jump_top", d.jumpTop);
    }

    /**
     * 单独回写跳转结果。
     * 跳转是异步确认的（onPause / 宽限期 / 超时三条路径），
     * 决策行往往先落库，之后才知道成功与否，所以需要一个只改这两列的入口。
     */
    public void updateJump(long id, int result, long costMs) {
        updateJump(id, result, costMs, null, null);
    }

    /** 带失败原因与前台 Activity 的版本（排查跳转问题用） */
    public void updateJump(long id, int result, long costMs, String reason, String top) {
        if (id <= 0) return;
        ContentValues cv = new ContentValues();
        cv.put("jump_result", result);
        cv.put("jump_cost_ms", costMs);
        cv.put("jump_reason", reason);
        cv.put("jump_top", top);
        try {
            getWritableDatabase().update("decisions", cv,
                    "id=?", new String[]{String.valueOf(id)});
        } catch (Throwable ignore) {}
    }

    /** 按通知 key 回写跳转结果（拿不到行 id 时的兜底，作用于最新一条同 key 记录） */
    public void updateJumpByKey(String notifKey, int result, long costMs) {
        updateJumpByKey(notifKey, result, costMs, null, null);
    }

    public void updateJumpByKey(String notifKey, int result, long costMs, String reason, String top) {
        if (notifKey == null) return;
        ContentValues cv = new ContentValues();
        cv.put("jump_result", result);
        cv.put("jump_cost_ms", costMs);
        cv.put("jump_reason", reason);
        cv.put("jump_top", top);
        try {
            getWritableDatabase().update("decisions", cv,
                    "notif_key=? AND id=(SELECT MAX(id) FROM decisions WHERE notif_key=?)",
                    new String[]{notifKey, notifKey});
        } catch (Throwable ignore) {}
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
        putTrace(cv, d);

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
        // v3 追踪字段（旧库刚升级时可能全为 null，按列存在性安全读取）
        d.intentPkg = getStr(c, "intent_pkg");
        d.intentTarget = getStr(c, "intent_target");
        d.intentAction = getStr(c, "intent_action");
        d.intentData = getStr(c, "intent_data");
        d.notifTag = getStr(c, "notif_tag");
        d.notifId = (int) getLong(c, "notif_id", 0);
        d.notifFlags = (int) getLong(c, "notif_flags", 0);
        d.notifCategory = getStr(c, "notif_category");
        d.notifChannel = getStr(c, "notif_channel");
        d.notifGroup = getStr(c, "notif_group");
        d.isGroupSummary = getLong(c, "is_group_summary", 0) == 1;
        d.postTime = getLong(c, "post_time", 0);
        d.jumpResult = (int) getLong(c, "jump_result", Decision.JUMP_NONE);
        d.jumpCostMs = getLong(c, "jump_cost_ms", -1);
        d.jumpReason = getStr(c, "jump_reason");
        d.jumpTop = getStr(c, "jump_top");
        return d;
    }

    private static String getStr(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getString(i) : null;
    }

    private static long getLong(Cursor c, String col, long def) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getLong(i) : def;
    }

    // ==================== 已屏蔽渠道 ====================
    /**
     * 记录一个被屏蔽的渠道。
     * 之前屏蔽只调 daemon，本地没留痕，导致：
     *   - 统计页分不清「已屏蔽」和「还在烦」
     *   - 重启后 UI 状态丢失
     * 现在统一落库，uid_channel 作主键（同 pkg|uid|channel）。
     */
    public void addMutedChannel(String uidChannel, String pkg, String channelId, String channelName) {
        ContentValues cv = new ContentValues();
        cv.put("uid_channel", uidChannel);
        cv.put("pkg", pkg);
        cv.put("channel_id", channelId);
        cv.put("channel_name", channelName);
        cv.put("muted_at", System.currentTimeMillis());
        try {
            getWritableDatabase().insertWithOnConflict(
                    "muted_channels", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable ignore) {}
    }

    /** 解除屏蔽 */
    public void removeMutedChannel(String uidChannel) {
        try {
            getWritableDatabase().delete("muted_channels",
                    "uid_channel=?", new String[]{uidChannel});
        } catch (Throwable ignore) {}
    }

    /** 所有已屏蔽渠道的 uid_channel 集合，用于过滤统计 */
    public java.util.Set<String> mutedChannelKeys() {
        java.util.Set<String> set = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT uid_channel FROM muted_channels", null)) {
            while (c.moveToNext()) {
                String k = c.getString(0);
                if (k != null) set.add(k);
            }
        } catch (Throwable ignore) {}
        return set;
    }

    public boolean isChannelMuted(String uidChannel) {
        if (uidChannel == null) return false;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM muted_channels WHERE uid_channel=? LIMIT 1",
                new String[]{uidChannel})) {
            return c.moveToFirst();
        } catch (Throwable ignore) {
            return false;
        }
    }

    // ==================== 统计（为将来的智能判断积累样本） ====================

    /** 某一决策类型的总次数 */
    public int countByType(int type) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM decisions WHERE type=?",
                new String[]{String.valueOf(type)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** 某类型在 since 之后的次数 */
    public int countByTypeSince(int type, long since) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM decisions WHERE type=? AND decided_at>=?",
                new String[]{String.valueOf(type), String.valueOf(since)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** 全部决策总数 */
    public int countAll() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM decisions", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** 单行统计结果：类型 → 计数 */
    public static class TypeCount {
        public int type;
        public int count;
        public TypeCount(int type, int count) { this.type = type; this.count = count; }
    }

    /** 今天 0 点的时间戳 */
    public static long todayStart() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 今天处理了多少条（所有决策类型，不含待办中间态） */
    public int countToday() {
        return countSince(todayStart());
    }

    /** 指定时间区间内的处理量 [from, to) */
    public int countBetween(long from, long to) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM decisions WHERE decided_at>=? AND decided_at<?",
                new String[]{String.valueOf(from), String.valueOf(to)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** 累计处理总量 */
    public int countTotal() {
        return countAll();
    }

    /** 单日统计：日期（yyyy-MM-dd）→ 处理条数 */
    public static class DayCount {
        public String day;     // MM-dd
        public long dayStart;
        public int count;
        public DayCount(String day, long dayStart, int count) {
            this.day = day; this.dayStart = dayStart; this.count = count;
        }
    }

    /**
     * 最近 days 天每天的处理量，按日期升序（老的在前），用于柱状图。
     * 没有数据的日子补 0，这样柱子间距才是均匀的。
     */
    public List<DayCount> statsByDay(int days) {
        java.util.LinkedHashMap<String, DayCount> map = new java.util.LinkedHashMap<>();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        cal.add(java.util.Calendar.DAY_OF_YEAR, -(days - 1));
        long from = cal.getTimeInMillis();
        for (int i = 0; i < days; i++) {
            String key = fmt.format(new java.util.Date(cal.getTimeInMillis()));
            map.put(key, new DayCount(key, cal.getTimeInMillis(), 0));
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        String sql = "SELECT decided_at FROM decisions WHERE decided_at>=?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(from)})) {
            while (c.moveToNext()) {
                String k = fmt.format(new java.util.Date(c.getLong(0)));
                DayCount dc = map.get(k);
                if (dc != null) dc.count++;
            }
        }
        return new ArrayList<>(map.values());
    }

    /** 单行：某 App 的处理分布（用于「哪些 App 最烦人」榜单） */
    public static class AppStat {
        public String pkg;
        public int total;
        public int ignore;
        public int seen;
        public int done;
        public int later;
        public float negativeRatio() {
            return total <= 0 ? 0f : (float) ignore / total;
        }
    }

    /**
     * 按 App 聚合。
     * @param excludeMuted true 时跳过已屏蔽渠道的记录 ——
     *        用户屏蔽了还统计它，只会让榜单里全是已经处理掉的噪音。
     */
    public List<AppStat> statsByApp(int minTotal, boolean excludeMuted) {
        java.util.Set<String> muted = excludeMuted ? mutedChannelKeys() : java.util.Collections.emptySet();
        java.util.LinkedHashMap<String, AppStat> map = new java.util.LinkedHashMap<>();
        String sql = "SELECT pkg, uid_channel, type, COUNT(*) FROM decisions GROUP BY pkg, uid_channel, type";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                String pkg = c.getString(0);
                String uc = c.getString(1);
                int type = c.getInt(2);
                int n = c.getInt(3);
                if (pkg == null) continue;
                if (uc != null && muted.contains(uc)) continue;
                AppStat st = map.get(pkg);
                if (st == null) {
                    st = new AppStat();
                    st.pkg = pkg;
                    map.put(pkg, st);
                }
                st.total += n;
                switch (type) {
                    case Decision.TYPE_SEEN:   st.seen += n; break;
                    case Decision.TYPE_IGNORE: st.ignore += n; break;
                    case Decision.TYPE_DONE:   st.done += n; break;
                    case Decision.TYPE_READ_LATER:
                    case Decision.TYPE_DO_LATER: st.later += n; break;
                    default: break;
                }
            }
        }
        List<AppStat> out = new ArrayList<>();
        for (AppStat st : map.values()) {
            if (st.total >= minTotal) out.add(st);
        }
        java.util.Collections.sort(out, (a, b) -> Integer.compare(b.total, a.total));
        return out;
    }

    /** 按类型分组统计（since 之后），用于统计页饼图/列表 */
    public List<TypeCount> statsByType(long since) {
        List<TypeCount> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT type, COUNT(*) AS n FROM decisions WHERE decided_at>=? "
                        + "GROUP BY type ORDER BY n DESC",
                new String[]{String.valueOf(since)})) {
            while (c.moveToNext()) list.add(new TypeCount(c.getInt(0), c.getInt(1)));
        }
        return list;
    }

    /** 单行统计：某个渠道（pkg+渠道名）的决策分布，用于将来判断"该不该屏蔽这个渠道" */
    public static class ChannelStat {
        public String pkg;
        public String channelName;
        /** pkg|uid|channel —— 用来查这张表是不是已经屏蔽了 */
        public String uidChannel;
        public int total;
        public int seen;      // 已阅
        public int ignore;    // 不感兴趣
        public int done;      // 已完成
        public int later;     // 稍后读/稍后办
        /** 负面占比：不感兴趣 / 总数 */
        public float negativeRatio() {
            return total <= 0 ? 0f : (float) ignore / total;
        }
    }

    /**
     * 按渠道聚合决策分布。
     * 这是「将来做智能判断」的基础数据：
     * 如果某个渠道的 ignore 占比长期很高，就可以建议用户直接屏蔽它。
     */
    public List<ChannelStat> statsByChannel(int minTotal) {
        return statsByChannel(minTotal, false, 0f);
    }

    /**
     * 按渠道聚合。
     *
     * @param minTotal     最少样本数，太少没有统计意义
     * @param excludeMuted 是否跳过已屏蔽渠道。用户已经屏蔽了就不该再看到它，
     *                     否则「建议屏蔽」列表里会一直挂着已经处理掉的东西。
     * @param minNegRatio  只保留「不感兴趣占比」达到这个阈值的渠道。
     *                     0 表示不过滤。统计页的「该考虑屏蔽了」用 0.4 之类的值。
     */
    public List<ChannelStat> statsByChannel(int minTotal, boolean excludeMuted, float minNegRatio) {
        java.util.Set<String> muted = excludeMuted ? mutedChannelKeys() : java.util.Collections.emptySet();
        List<ChannelStat> list = new ArrayList<>();
        String sql = "SELECT pkg, channel_name, uid_channel, type, COUNT(*) AS n FROM decisions "
                + "GROUP BY pkg, channel_name, uid_channel, type";
        java.util.LinkedHashMap<String, ChannelStat> map = new java.util.LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                String pkg = c.getString(0);
                String ch = c.getString(1);
                String uc = c.getString(2);
                int type = c.getInt(3);
                int n = c.getInt(4);
                // 已屏蔽的渠道直接跳过，不进统计（用户已经处理过它了）
                if (uc != null && muted.contains(uc)) continue;
                String k = pkg + "\u0001" + ch;
                ChannelStat st = map.get(k);
                if (st == null) {
                    st = new ChannelStat();
                    st.pkg = pkg;
                    st.channelName = ch;
                    st.uidChannel = uc;
                    map.put(k, st);
                }
                st.total += n;
                switch (type) {
                    case Decision.TYPE_SEEN:  st.seen += n; break;
                    case Decision.TYPE_IGNORE: st.ignore += n; break;
                    case Decision.TYPE_DONE:  st.done += n; break;
                    case Decision.TYPE_READ_LATER:
                    case Decision.TYPE_DO_LATER: st.later += n; break;
                    default: break;
                }
            }
        }
        for (ChannelStat st : map.values()) {
            if (st.total < minTotal) continue;
            if (minNegRatio > 0f && st.negativeRatio() < minNegRatio) continue;
            list.add(st);
        }
        // 负面占比高的排前面 —— 最可能是"该屏蔽"的渠道
        java.util.Collections.sort(list, (a, b) ->
                Float.compare(b.negativeRatio(), a.negativeRatio()));
        return list;
    }

    /** 小时分布：一天中哪个时段收到的通知最多（0-23） */
    public int[] hourHistogram() {
        int[] h = new int[24];
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT CAST(strftime('%H', decided_at/1000, 'unixepoch', 'localtime') AS INTEGER) AS h, "
                        + "COUNT(*) FROM decisions GROUP BY h", null)) {
            while (c.moveToNext()) {
                int idx = c.getInt(0);
                if (idx >= 0 && idx < 24) h[idx] = c.getInt(1);
            }
        }
        return h;
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