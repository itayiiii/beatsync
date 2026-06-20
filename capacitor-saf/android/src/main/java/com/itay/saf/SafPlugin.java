package com.itay.saf;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Storage Access Framework bridge for beat.sync.
 *
 * Lets the WebView pick a music folder once (ACTION_OPEN_DOCUMENT_TREE), keep
 * permanent read access to it (takePersistableUriPermission), list its contents
 * and read individual files in place by their content:// URI — without copying
 * anything into app storage.
 */
@CapacitorPlugin(name = "Saf")
public class SafPlugin extends Plugin {

    // ── Pick a folder ────────────────────────────────────────────────────────
    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );
        startActivityForResult(call, intent, "pickFolderResult");
    }

    @ActivityCallback
    private void pickFolderResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.reject("cancelled");
            return;
        }
        Uri treeUri = data.getData();
        try {
            int takeFlags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (takeFlags == 0) takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContext().getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
        } catch (Exception e) {
            // Permission may still be usable for this session even if persist failed.
        }
        JSObject ret = new JSObject();
        ret.put("uri", treeUri.toString());
        ret.put("name", queryRootName(treeUri));
        call.resolve(ret);
    }

    // ── List immediate children of a directory ──────────────────────────────
    @PluginMethod
    public void listChildren(PluginCall call) {
        String uriStr = call.getString("uri");
        if (uriStr == null) { call.reject("uri required"); return; }
        Uri dirUri = Uri.parse(uriStr);

        String parentDocId;
        try {
            parentDocId = DocumentsContract.getDocumentId(dirUri);
        } catch (Exception e) {
            // Bare tree URI (the root) has no /document segment yet.
            parentDocId = DocumentsContract.getTreeDocumentId(dirUri);
        }

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, parentDocId);
        ContentResolver cr = getContext().getContentResolver();
        JSArray items = new JSArray();
        Cursor c = null;
        try {
            c = cr.query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
            }, null, null, null);
            while (c != null && c.moveToNext()) {
                String docId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0L : c.getLong(3);
                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId);
                JSObject o = new JSObject();
                o.put("name", name == null ? docId : name);
                o.put("uri", childUri.toString());
                o.put("isDirectory", isDir);
                o.put("size", size);
                items.put(o);
            }
            JSObject ret = new JSObject();
            ret.put("items", items);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("listChildren failed: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
    }

    // ── Read a file (optionally a byte window) as base64 ─────────────────────
    @PluginMethod
    public void readFile(PluginCall call) {
        String uriStr = call.getString("uri");
        if (uriStr == null) { call.reject("uri required"); return; }
        int offset = call.getInt("offset", 0);
        Integer length = call.getInt("length"); // null = read to end
        Uri uri = Uri.parse(uriStr);
        ContentResolver cr = getContext().getContentResolver();
        InputStream is = null;
        try {
            is = cr.openInputStream(uri);
            if (is == null) { call.reject("openInputStream returned null"); return; }

            // Skip to offset.
            long toSkip = offset;
            while (toSkip > 0) {
                long s = is.skip(toSkip);
                if (s <= 0) {
                    if (is.read() < 0) break; // EOF
                    toSkip -= 1;
                } else {
                    toSkip -= s;
                }
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] tmp = new byte[65536];
            long remaining = (length == null) ? Long.MAX_VALUE : length.longValue();
            int n;
            while (remaining > 0) {
                int want = (int) Math.min((long) tmp.length, remaining);
                n = is.read(tmp, 0, want);
                if (n == -1) break;
                bos.write(tmp, 0, n);
                if (length != null) remaining -= n;
            }

            byte[] bytes = bos.toByteArray();
            String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            String mime = cr.getType(uri);

            JSObject ret = new JSObject();
            ret.put("data", b64);
            ret.put("mime", mime == null ? "" : mime);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("readFile failed: " + e.getMessage());
        } finally {
            if (is != null) { try { is.close(); } catch (Exception ignore) {} }
        }
    }

    // ── Helper: display name of a tree's root document ───────────────────────
    private String queryRootName(Uri treeUri) {
        Cursor c = null;
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            c = getContext().getContentResolver().query(
                    docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception e) {
            // fall through
        } finally {
            if (c != null) c.close();
        }
        return "Music";
    }
}
