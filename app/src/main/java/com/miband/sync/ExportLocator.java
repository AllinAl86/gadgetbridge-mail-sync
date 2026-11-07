package com.miband.sync;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 读取用户授权过的导出文件或文件夹。
 * Gadgetbridge 把数据库写到用户自己选的位置，文件名经常是不带后缀的 Gadgetbridge。
 */
public final class ExportLocator {

    static final String PREFS = "mail_sync_prefs";
    private static final String KEY_URI = "export_uri";
    private static final String KEY_TREE = "export_is_tree";
    private static final String KEY_NAME = "export_name";

    private ExportLocator() {
    }

    static final class Meta {
        Uri uri;
        String name = "";
        long size = -1;
        long modified = -1;
    }

    /** @return 是否拿到了重启后仍然有效的读取授权 */
    static boolean save(Context context, Uri uri, boolean tree) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString(KEY_URI, "");
        if (!old.isEmpty()) {
            try {
                context.getContentResolver().releasePersistableUriPermission(
                        Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        boolean persisted = true;
        try {
            context.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
            persisted = false;
        }
        String name = displayName(context, uri);
        prefs.edit()
                .putString(KEY_URI, uri.toString())
                .putBoolean(KEY_TREE, tree)
                .putString(KEY_NAME, name == null ? "" : name)
                .apply();
        return persisted;
    }

    static String savedName(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, "");
    }

    static boolean hasTarget(Context context) {
        return uri(context) != null;
    }

    static Meta snapshot(Context context) {
        Uri root = uri(context);
        if (root == null) {
            return null;
        }
        if (isTree(context)) {
            Meta newest = null;
            for (Meta meta : listDbFiles(context, root)) {
                if (newest == null || meta.modified > newest.modified) {
                    newest = meta;
                }
            }
            return newest;
        }
        Meta meta = queryMeta(context, root);
        meta.uri = root;
        return meta;
    }

    /** 找出这次导出后更新过的数据库。找不到则返回 null。 */
    static Meta findUpdated(Context context, Meta before, long startedAt) {
        Uri root = uri(context);
        if (root == null) {
            return null;
        }
        List<Meta> candidates = new ArrayList<>();
        if (isTree(context)) {
            candidates.addAll(listDbFiles(context, root));
        } else {
            Meta meta = queryMeta(context, root);
            meta.uri = root;
            if (nameMaybeDb(meta.name) || isSqlite(context, root)) {
                candidates.add(meta);
            }
        }
        Meta chosen = null;
        for (Meta meta : candidates) {
            if (!isUpdated(before, meta, startedAt)) {
                continue;
            }
            if (!isSqlite(context, meta.uri)) {
                continue;
            }
            if (chosen == null || prefer(meta, chosen)) {
                chosen = meta;
            }
        }
        return chosen;
    }

    static File copyToCache(Context context, Uri uri) throws IOException {
        File out = new File(context.getCacheDir(), "Gadgetbridge.sqlite");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(out)) {
            if (in == null) {
                throw new IOException("无法读取导出文件");
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                output.write(buf, 0, n);
            }
        }
        return out;
    }

    private static boolean prefer(Meta candidate, Meta current) {
        boolean candidateNamed = candidate.name.toLowerCase(Locale.US).contains("gadgetbridge");
        boolean currentNamed = current.name.toLowerCase(Locale.US).contains("gadgetbridge");
        if (candidateNamed != currentNamed) {
            return candidateNamed;
        }
        return candidate.modified > current.modified;
    }

    private static boolean isUpdated(Meta before, Meta now, long startedAt) {
        if (now == null) {
            return false;
        }
        boolean sameFile = before != null && before.uri != null && before.uri.equals(now.uri);
        if (now.modified > 0 && now.modified >= startedAt - 5000) {
            if (!sameFile || before.modified <= 0 || now.modified > before.modified) {
                return true;
            }
            if (before.size >= 0 && now.size >= 0 && now.size != before.size) {
                return true;
            }
        }
        return sameFile && before.size >= 0 && now.size > 0 && now.size != before.size;
    }

    private static List<Meta> listDbFiles(Context context, Uri treeUri) {
        List<Meta> list = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        try (Cursor cursor = context.getContentResolver().query(children, null, null, null, null)) {
            if (cursor == null) {
                return list;
            }
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String mime = mimeIndex >= 0 ? cursor.getString(mimeIndex) : "";
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    continue;
                }
                String name = nameIndex >= 0 ? cursor.getString(nameIndex) : "";
                if (!nameMaybeDb(name)) {
                    continue;
                }
                String docId = idIndex >= 0 ? cursor.getString(idIndex) : null;
                if (docId == null) {
                    continue;
                }
                Meta meta = readMeta(cursor);
                meta.name = name == null ? "" : name;
                meta.uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                list.add(meta);
            }
        } catch (Exception ignored) {
            return list;
        }
        return list;
    }

    private static Meta queryMeta(Context context, Uri uri) {
        Meta meta = new Meta();
        meta.uri = uri;
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                Meta fromCursor = readMeta(cursor);
                meta.size = fromCursor.size;
                meta.modified = fromCursor.modified;
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    meta.name = cursor.getString(nameIndex);
                }
            }
        } catch (Exception ignored) {
        }
        if (meta.name == null || meta.name.isEmpty()) {
            meta.name = savedName(context);
        }
        return meta;
    }

    private static Meta readMeta(Cursor cursor) {
        Meta meta = new Meta();
        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
        if (sizeIndex < 0) {
            sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
        }
        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
            meta.size = cursor.getLong(sizeIndex);
        }
        int modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
        if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
            long value = cursor.getLong(modifiedIndex);
            if (value > 0 && value < 1_000_000_000_000L) {
                value *= 1000L;
            }
            meta.modified = value;
        }
        return meta;
    }

    private static boolean nameMaybeDb(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".xml") || lower.endsWith(".gpx") || lower.endsWith(".zip")
                || lower.endsWith(".fit") || lower.endsWith(".png") || lower.endsWith(".jpg")) {
            return false;
        }
        return lower.endsWith(".db")
                || lower.endsWith(".sqlite")
                || lower.endsWith(".sqlite3")
                || lower.equals("gadgetbridge")
                || lower.startsWith("gadgetbridge");
    }

    static boolean isSqlite(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return false;
            }
            byte[] buf = new byte[16];
            int n = 0;
            while (n < buf.length) {
                int read = in.read(buf, n, buf.length - n);
                if (read < 0) {
                    break;
                }
                n += read;
            }
            if (n < 15) {
                return false;
            }
            String header = new String(buf, 0, 15, StandardCharsets.US_ASCII);
            return "SQLite format 3".equals(header);
        } catch (Exception e) {
            return false;
        }
    }

    private static String displayName(Context context, Uri uri) {
        Meta meta = queryMeta(context, uri);
        return meta.name;
    }

    private static Uri uri(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URI, "");
        if (value == null || value.isEmpty()) {
            return null;
        }
        return Uri.parse(value);
    }

    static boolean targetsTree(Context context) {
        return isTree(context);
    }

    private static boolean isTree(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TREE, false);
    }
}
