package la.launcheranywhere;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbFile;

/**
 * TV-first LAN SMB file browser:
 * 1. Fast subnet port 445 scan with NetBIOS hostname discovery (RFC 1002).
 * 2. Visual server cards, 1-click guest connect or remembered credentials.
 * 3. Modern Apple TV / frosted glass UI with smooth D-pad remote navigation.
 * 4. Browse shares/folders with breadcrumbs, download APKs with visual progress and launch system installer.
 */
public class SmbActivity extends Activity {

    private static final String PREFS = "smb_config";
    private static final String PREF_SAVED_HOSTS = "saved_hosts";
    private static final String AUTHORITY = "la.launcheranywhere.fileprovider";

    // View Modes
    private static final int MODE_SERVERS = 0;
    private static final int MODE_FILES = 1;

    // Item Types
    private static final int TYPE_SHARE = 1;
    private static final int TYPE_DIR = 2;
    private static final int TYPE_APK = 3;
    private static final int TYPE_OTHER = 4;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // UI elements
    private TextView tvTitle;
    private TextView tvStatus;
    private TextView btnScan;
    private TextView btnManual;
    private TextView btnAccount;
    private View scrollBreadcrumbs;
    private LinearLayout breadcrumbContainer;

    private RecyclerView rvServers;
    private RecyclerView rvFiles;
    private View emptyLayout;
    private TextView tvEmptyMessage;
    private View loadingLayout;
    private TextView tvLoadingText;

    // Auth Overlay Dialog
    private FrameLayout authOverlay;
    private TextView dialogAuthTitle;
    private TextView dialogAuthSubtitle;
    private EditText editServerHost;
    private EditText editServerUser;
    private EditText editServerPass;
    private CheckBox cbRememberCreds;
    private View btnAuthGuest;
    private View btnAuthCancel;
    private View btnAuthConfirm;

    // Download Overlay Dialog
    private FrameLayout downloadOverlay;
    private TextView tvDownloadFilename;
    private ProgressBar downloadProgressbar;
    private TextView tvDownloadProgressText;
    private View btnDownloadCancel;

    // State data
    private int currentMode = MODE_SERVERS;
    private boolean isBusy = false;
    private final List<ServerItem> serverList = new ArrayList<>();
    private final List<FileItem> fileList = new ArrayList<>();
    private final List<Breadcrumb> breadcrumbs = new ArrayList<>();

    private ServerAdapter serverAdapter;
    private FileAdapter fileAdapter;

    // Active SMB session
    private CIFSContext cifsContext;
    private SmbFile rootShareSmb;
    private SmbFile currentFolderSmb;
    private ServerItem activeServer;
    private String pendingShareName;

    // Download cancellation tracking
    private final AtomicBoolean downloadCancelled = new AtomicBoolean(false);
    private Future<?> downloadTask;

    /* ---------------- Data Models ---------------- */

    static class ServerItem {
        String name;
        String host;
        boolean hasSavedCreds;

        ServerItem(String name, String host, boolean hasSavedCreds) {
            this.name = (name != null && !name.trim().isEmpty()) ? name : host;
            this.host = host;
            this.hasSavedCreds = hasSavedCreds;
        }
    }

    static class FileItem {
        String name;
        int type;
        long size;
        SmbFile smbFile;

        FileItem(String name, int type, long size, SmbFile smbFile) {
            this.name = name;
            this.type = type;
            this.size = size;
            this.smbFile = smbFile;
        }
    }

    static class Breadcrumb {
        String title;
        SmbFile folder;

        Breadcrumb(String title, SmbFile folder) {
            this.title = title;
            this.folder = folder;
        }
    }

    /* ---------------- Lifecycle ---------------- */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smb);

        initViews();
        loadSavedServers();
        startScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tv_title);
        tvStatus = findViewById(R.id.tv_status);
        btnScan = findViewById(R.id.btn_scan);
        btnManual = findViewById(R.id.btn_manual);
        btnAccount = findViewById(R.id.btn_account);
        scrollBreadcrumbs = findViewById(R.id.scroll_breadcrumbs);
        breadcrumbContainer = findViewById(R.id.breadcrumb_container);

        rvServers = findViewById(R.id.rv_servers);
        rvFiles = findViewById(R.id.rv_files);
        emptyLayout = findViewById(R.id.empty_layout);
        tvEmptyMessage = findViewById(R.id.tv_empty_message);
        loadingLayout = findViewById(R.id.loading_layout);
        tvLoadingText = findViewById(R.id.tv_loading_text);

        authOverlay = findViewById(R.id.auth_overlay);
        dialogAuthTitle = findViewById(R.id.dialog_auth_title);
        dialogAuthSubtitle = findViewById(R.id.dialog_auth_subtitle);
        editServerHost = findViewById(R.id.edit_server_host);
        editServerUser = findViewById(R.id.edit_server_user);
        editServerPass = findViewById(R.id.edit_server_pass);
        cbRememberCreds = findViewById(R.id.cb_remember_creds);
        btnAuthGuest = findViewById(R.id.btn_auth_guest);
        btnAuthCancel = findViewById(R.id.btn_auth_cancel);
        btnAuthConfirm = findViewById(R.id.btn_auth_confirm);

        downloadOverlay = findViewById(R.id.download_overlay);
        tvDownloadFilename = findViewById(R.id.tv_download_filename);
        downloadProgressbar = findViewById(R.id.download_progressbar);
        tvDownloadProgressText = findViewById(R.id.tv_download_progress_text);
        btnDownloadCancel = findViewById(R.id.btn_download_cancel);

        // Adapters
        serverAdapter = new ServerAdapter();
        rvServers.setLayoutManager(new GridLayoutManager(this, 3));
        rvServers.setAdapter(serverAdapter);

        fileAdapter = new FileAdapter();
        rvFiles.setLayoutManager(new LinearLayoutManager(this));
        rvFiles.setAdapter(fileAdapter);

        // Header listeners
        btnScan.setOnClickListener(v -> startScan());
        btnManual.setOnClickListener(v -> showAuthDialog("", "", "", true));
        btnAccount.setOnClickListener(v -> {
            String[] creds = activeServer != null ? getSavedCreds(activeServer.host) : null;
            showAuthDialog(activeServer != null ? activeServer.host : "",
                    creds != null ? creds[0] : "",
                    creds != null ? creds[1] : "",
                    false);
        });

        // Auth Dialog listeners
        btnAuthGuest.setOnClickListener(v -> {
            String host = editServerHost.getText().toString().trim();
            if (host.isEmpty()) {
                toast("请输入服务器 IP");
                return;
            }
            authOverlay.setVisibility(View.GONE);
            String name = activeServer != null && activeServer.host.equals(host) ? activeServer.name : host;
            connectToServer(new ServerItem(name, host, false), "", "", false);
        });

        btnAuthConfirm.setOnClickListener(v -> {
            String host = editServerHost.getText().toString().trim();
            String user = editServerUser.getText().toString().trim();
            String pass = editServerPass.getText().toString();
            boolean remember = cbRememberCreds.isChecked();

            if (host.isEmpty()) {
                toast("请输入服务器 IP");
                return;
            }
            authOverlay.setVisibility(View.GONE);
            String name = activeServer != null && activeServer.host.equals(host) ? activeServer.name : host;
            connectToServer(new ServerItem(name, host, remember), user, pass, remember);
        });

        btnAuthCancel.setOnClickListener(v -> authOverlay.setVisibility(View.GONE));

        // Download Cancel
        btnDownloadCancel.setOnClickListener(v -> cancelDownload());
    }

    /* ---------------- LAN Subnet Scan & NetBIOS Name Discovery ---------------- */

    private void loadSavedServers() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedRaw = sp.getString(PREF_SAVED_HOSTS, "");
        if (!savedRaw.isEmpty()) {
            for (String host : savedRaw.split(",")) {
                if (!host.trim().isEmpty()) {
                    String[] creds = getSavedCreds(host);
                    String name = sp.getString("name_" + host, host);
                    serverList.add(new ServerItem(name, host, creds != null));
                }
            }
            serverAdapter.notifyDataSetChanged();
            updateServerEmptyState();
        }
    }

    private void startScan() {
        if (isBusy) return;
        setMode(MODE_SERVERS);
        showLoading("正在扫描局域网中的共享设备…");
        setStatus("正在扫描局域网 (端口 445)…");

        pool.execute(() -> {
            List<String> ips = getSubnetIps();
            if (ips.isEmpty()) {
                ui.post(() -> {
                    hideLoading();
                    setStatus("未能获取局域网地址，请使用右上角手动连接");
                    updateServerEmptyState();
                });
                return;
            }

            CopyOnWriteArrayList<ServerItem> discovered = new CopyOnWriteArrayList<>();
            ExecutorService scanPool = Executors.newFixedThreadPool(32);

            for (String ip : ips) {
                scanPool.execute(() -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(ip, 445), 500);
                        // Port 445 is open! Resolve NetBIOS computer name
                        String resolvedName = resolveNetBiosOrDnsName(ip);
                        String[] creds = getSavedCreds(ip);
                        discovered.add(new ServerItem(resolvedName, ip, creds != null));

                        // Dynamically update UI when a device is found
                        ui.post(() -> {
                            mergeDiscoveredServer(new ServerItem(resolvedName, ip, creds != null));
                            setStatus(String.format(Locale.getDefault(), "已发现 %d 台设备，仍在扫描…", serverList.size()));
                        });
                    } catch (Exception ignored) {
                    }
                });
            }

            scanPool.shutdown();
            try {
                scanPool.awaitTermination(8, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}

            ui.post(() -> {
                hideLoading();
                if (serverList.isEmpty()) {
                    setStatus("未发现局域网 SMB 设备，可尝试右上角手动输入 IP");
                } else {
                    setStatus(String.format(Locale.getDefault(), "扫描完成，共发现 %d 台设备", serverList.size()));
                }
                updateServerEmptyState();
            });
        });
    }

    private void mergeDiscoveredServer(ServerItem item) {
        for (int i = 0; i < serverList.size(); i++) {
            if (serverList.get(i).host.equals(item.host)) {
                serverList.set(i, item);
                serverAdapter.notifyItemChanged(i);
                return;
            }
        }
        serverList.add(item);
        serverAdapter.notifyItemInserted(serverList.size() - 1);
        hideLoading();
        emptyLayout.setVisibility(View.GONE);
        rvServers.setVisibility(View.VISIBLE);
    }

    private void updateServerEmptyState() {
        if (currentMode == MODE_SERVERS) {
            boolean empty = serverList.isEmpty();
            emptyLayout.setVisibility(empty ? View.VISIBLE : View.GONE);
            rvServers.setVisibility(empty ? View.GONE : View.VISIBLE);
            if (empty) tvEmptyMessage.setText("未发现局域网共享设备");
        }
    }

    /**
     * Resolves NetBIOS Node Status name over UDP port 137 (RFC 1002).
     * Works on Windows, Samba, Synology, QNAP, TrueNAS, etc.
     */
    private static String resolveNetBiosOrDnsName(String ip) {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(600);
            byte[] request = new byte[] {
                    (byte) 0x82, (byte) 0x28, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x20, 0x43, 0x4B, 0x41, 0x41, 0x41,
                    0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                    0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                    0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x00, 0x00, 0x21,
                    0x00, 0x01
            };
            DatagramPacket packet = new DatagramPacket(request, request.length, InetAddress.getByName(ip), 137);
            socket.send(packet);

            byte[] buffer = new byte[1024];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            socket.close();

            int offset = 56;
            if (response.getLength() > offset) {
                int numNames = buffer[offset] & 0xFF;
                if (numNames > 0 && response.getLength() >= offset + 1 + 18) {
                    String name = new String(buffer, offset + 1, 15, "ASCII").trim();
                    if (!name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {}

        // Fallback: reverse DNS
        try {
            String host = InetAddress.getByName(ip).getCanonicalHostName();
            if (host != null && !host.equals(ip)) {
                if (host.endsWith(".local")) host = host.substring(0, host.length() - 6);
                return host;
            }
        } catch (Exception ignored) {}
        return ip;
    }

    private List<String> getSubnetIps() {
        List<String> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                        for (int i = 1; i < 255; i++) {
                            list.add(prefix + i);
                        }
                        return list;
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    /* ---------------- SMB Connection & Browsing ---------------- */

    private void onServerClick(ServerItem server) {
        String[] creds = getSavedCreds(server.host);
        if (creds != null) {
            connectToServer(server, creds[0], creds[1], true);
        } else {
            // Try Guest/Anonymous connect first
            showLoading("正在连接 " + server.name + "…");
            pool.execute(() -> {
                try {
                    CIFSContext testCtx = createCifsContext("", "");
                    SmbFile smb = new SmbFile("smb://" + server.host + "/", testCtx);
                    smb.listFiles(); // Test permission
                    // Guest succeeded!
                    ui.post(() -> connectToServer(server, "", "", false));
                } catch (SmbAuthException e) {
                    // Auth needed: show sleek dialog
                    ui.post(() -> {
                        hideLoading();
                        showAuthDialog(server.host, "", "", false);
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        hideLoading();
                        showAuthDialog(server.host, "", "", false);
                    });
                }
            });
        }
    }

    private void connectToServer(ServerItem server, String user, String pass, boolean remember) {
        activeServer = server;
        showLoading("正在连接 " + server.name + "…");

        pool.execute(() -> {
            try {
                cifsContext = createCifsContext(user, pass);
                rootShareSmb = new SmbFile("smb://" + server.host + "/", cifsContext);
                SmbFile[] shares = rootShareSmb.listFiles();

                if (remember && user != null && !user.trim().isEmpty()) {
                    saveCreds(server.host, server.name, user, pass);
                    server.hasSavedCreds = true;
                }

                List<FileItem> items = new ArrayList<>();
                for (SmbFile s : shares) {
                    String name = s.getName();
                    // Filter out administrative shares by default (e.g. C$, IPC$)
                    if (name.endsWith("$") || name.equalsIgnoreCase("IPC$/")) continue;
                    String cleanName = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                    SmbFile shareSmb = new SmbFile("smb://" + server.host + "/" + cleanName + "/", cifsContext);
                    items.add(new FileItem(cleanName, TYPE_SHARE, 0, shareSmb));
                }

                ui.post(() -> {
                    hideLoading();
                    setMode(MODE_FILES);
                    currentFolderSmb = null;
                    breadcrumbs.clear();
                    breadcrumbs.add(new Breadcrumb(server.name, null));
                    updateBreadcrumbsUi();

                    fileList.clear();
                    fileList.addAll(items);
                    fileAdapter.notifyDataSetChanged();
                    setStatus(String.format(Locale.getDefault(), "已连接 %s，选择共享目录 (%d 个)", server.name, items.size()));

                    // Auto-enter pending share if authentication was just completed for it
                    if (pendingShareName != null) {
                        String target = pendingShareName;
                        pendingShareName = null;
                        for (FileItem fi : items) {
                            if (fi.name.equalsIgnoreCase(target)) {
                                enterDirectory(fi.smbFile, fi.name);
                                break;
                            }
                        }
                    }
                });
            } catch (SmbAuthException authEx) {
                ui.post(() -> {
                    hideLoading();
                    showAuthDialog(server.host, user, pass, false);
                    toast("登录失败：账号或密码错误");
                });
            } catch (Exception e) {
                ui.post(() -> {
                    hideLoading();
                    setStatus("连接失败：" + e.getMessage());
                    showAuthDialog(server.host, user, pass, false);
                    toast("连接失败，请检查账号密码或网络");
                });
            }
        });
    }

    private CIFSContext createCifsContext(String user, String pass) throws Exception {
        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.minVersion", "SMB202");
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        props.setProperty("jcifs.smb.client.responseTimeout", "15000");
        props.setProperty("jcifs.smb.client.soTimeout", "20000");
        props.setProperty("jcifs.smb.client.connTimeout", "8000");
        props.setProperty("jcifs.smb.client.signingEnforced", "false");
        props.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");

        BaseContext base = new BaseContext(new PropertyConfiguration(props));
        if (user == null || user.trim().isEmpty()) {
            return base.withGuestCrendentials();
        } else {
            return base.withCredentials(new NtlmPasswordAuthenticator(null, user.trim(), pass));
        }
    }

    private void enterDirectory(SmbFile dir, String displayName) {
        showLoading("正在读取目录 " + displayName + "…");
        pool.execute(() -> {
            try {
                SmbFile[] files = dir.listFiles();
                List<FileItem> list = new ArrayList<>();

                for (SmbFile f : files) {
                    String name = f.getName();
                    String cleanName = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                    boolean isDir = f.isDirectory();
                    int type = isDir ? TYPE_DIR : (cleanName.toLowerCase().endsWith(".apk") ? TYPE_APK : TYPE_OTHER);
                    long size = isDir ? 0 : f.getContentLength();
                    SmbFile childSmb = isDir ? new SmbFile(dir, cleanName + "/") : f;
                    list.add(new FileItem(cleanName, type, size, childSmb));
                }

                // Sort: Directories first, then APKs, then others
                Collections.sort(list, (a, b) -> {
                    if (a.type == TYPE_DIR && b.type != TYPE_DIR) return -1;
                    if (a.type != TYPE_DIR && b.type == TYPE_DIR) return 1;
                    if (a.type == TYPE_APK && b.type != TYPE_APK) return -1;
                    if (a.type != TYPE_APK && b.type == TYPE_APK) return 1;
                    return a.name.compareToIgnoreCase(b.name);
                });

                ui.post(() -> {
                    hideLoading();
                    pendingShareName = null;
                    currentFolderSmb = dir;
                    breadcrumbs.add(new Breadcrumb(displayName, dir));
                    updateBreadcrumbsUi();

                    fileList.clear();
                    fileList.addAll(list);
                    fileAdapter.notifyDataSetChanged();
                    rvFiles.scrollToPosition(0);
                    setStatus(String.format(Locale.getDefault(), "%s (包含 %d 个条目)", displayName, list.size()));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    hideLoading();
                    pendingShareName = displayName;
                    setStatus("访问受限：" + e.getMessage());
                    String[] saved = activeServer != null ? getSavedCreds(activeServer.host) : null;
                    showAuthDialog(activeServer != null ? activeServer.host : "",
                            saved != null ? saved[0] : "",
                            saved != null ? saved[1] : "",
                            false);
                    dialogAuthTitle.setText("需要登录认证");
                    dialogAuthSubtitle.setText("共享 [" + displayName + "] 需要账号密码访问");
                    toast("该共享需要账号密码验证，请输入凭据");
                });
            }
        });
    }

    private void navigateBackDirectory() {
        if (breadcrumbs.size() > 2) {
            // Go to parent directory
            breadcrumbs.remove(breadcrumbs.size() - 1);
            Breadcrumb target = breadcrumbs.get(breadcrumbs.size() - 1);
            currentFolderSmb = target.folder;
            enterDirectory(target.folder, target.title);
            // remove duplicated breadcrumb that enterDirectory appends
            breadcrumbs.remove(breadcrumbs.size() - 1);
        } else if (breadcrumbs.size() == 2) {
            // Return to server root shares
            breadcrumbs.remove(breadcrumbs.size() - 1);
            connectToServer(activeServer, "", "", false);
        } else {
            // Disconnect and return to server list
            disconnectAndShowServers();
        }
    }

    private void disconnectAndShowServers() {
        cifsContext = null;
        rootShareSmb = null;
        currentFolderSmb = null;
        activeServer = null;
        setMode(MODE_SERVERS);
        setStatus("请选择要连接的共享设备");
        updateServerEmptyState();
    }

    /* ---------------- Breadcrumbs UI ---------------- */

    private void updateBreadcrumbsUi() {
        breadcrumbContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < breadcrumbs.size(); i++) {
            final int index = i;
            Breadcrumb b = breadcrumbs.get(i);
            View v = inflater.inflate(R.layout.item_smb_breadcrumb, breadcrumbContainer, false);
            TextView titleView = v.findViewById(R.id.tv_crumb_title);
            titleView.setText(b.title);

            titleView.setOnClickListener(view -> {
                while (breadcrumbs.size() > index + 1) {
                    breadcrumbs.remove(breadcrumbs.size() - 1);
                }
                Breadcrumb target = breadcrumbs.get(index);
                if (target.folder == null) {
                    connectToServer(activeServer, "", "", false);
                } else {
                    enterDirectory(target.folder, target.title);
                    breadcrumbs.remove(breadcrumbs.size() - 1);
                }
            });
            breadcrumbContainer.addView(v);
        }
    }

    /* ---------------- APK Download & System Install ---------------- */

    private void onFileItemClick(FileItem item) {
        if (item.type == TYPE_SHARE || item.type == TYPE_DIR) {
            enterDirectory(item.smbFile, item.name);
        } else if (item.type == TYPE_APK) {
            downloadAndInstallApk(item);
        } else {
            toast("此文件格式暂不支持，请点选 APK 安装包");
        }
    }

    private void downloadAndInstallApk(FileItem item) {
        if (isBusy) return;
        downloadCancelled.set(false);

        downloadOverlay.setVisibility(View.VISIBLE);
        tvDownloadFilename.setText(item.name);
        downloadProgressbar.setProgress(0);
        tvDownloadProgressText.setText("0% (准备下载…)");

        downloadTask = pool.submit(() -> {
            File cacheDir = new File(getCacheDir(), "apks");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File dst = new File(cacheDir, item.name);

            try (InputStream in = item.smbFile.getInputStream();
                 FileOutputStream out = new FileOutputStream(dst)) {

                long totalBytes = item.size > 0 ? item.size : item.smbFile.getContentLength();
                byte[] buf = new byte[256 * 1024];
                int read;
                long transferred = 0;
                long lastProgressTime = 0;

                while ((read = in.read(buf)) > 0) {
                    if (downloadCancelled.get()) {
                        dst.delete();
                        return;
                    }
                    out.write(buf, 0, read);
                    transferred += read;

                    long now = System.currentTimeMillis();
                    if (now - lastProgressTime > 120 || transferred == totalBytes) {
                        lastProgressTime = now;
                        final int progress = totalBytes > 0 ? (int) ((transferred * 100) / totalBytes) : 0;
                        final String progressStr = String.format(Locale.getDefault(),
                                "%d%% (%s / %s)",
                                progress,
                                formatSize(transferred),
                                totalBytes > 0 ? formatSize(totalBytes) : "未知");

                        ui.post(() -> {
                            downloadProgressbar.setProgress(progress);
                            tvDownloadProgressText.setText(progressStr);
                        });
                    }
                }
                out.flush();

                if (!downloadCancelled.get()) {
                    ui.post(() -> {
                        downloadOverlay.setVisibility(View.GONE);
                        launchPackageInstaller(dst);
                    });
                }
            } catch (Exception e) {
                if (!downloadCancelled.get()) {
                    dst.delete();
                    ui.post(() -> {
                        downloadOverlay.setVisibility(View.GONE);
                        toast("获取 APK 失败：" + e.getMessage());
                    });
                }
            }
        });
    }

    private void cancelDownload() {
        downloadCancelled.set(true);
        if (downloadTask != null) downloadTask.cancel(true);
        downloadOverlay.setVisibility(View.GONE);
        toast("已取消下载");
    }

    private void launchPackageInstaller(File apkFile) {
        try {
            Uri contentUri = FileProvider.getUriForFile(this, AUTHORITY, apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            toast("已启动安装程序，请按电视提示安装");
        } catch (Exception e) {
            toast("启动安装程序失败：" + e.getMessage());
        }
    }

    /* ---------------- Remote Key Navigation ---------------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (downloadOverlay.getVisibility() == View.VISIBLE) {
                cancelDownload();
                return true;
            }
            if (authOverlay.getVisibility() == View.VISIBLE) {
                authOverlay.setVisibility(View.GONE);
                return true;
            }
            if (currentMode == MODE_FILES) {
                navigateBackDirectory();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /* ---------------- UI State Helpers ---------------- */

    private void setMode(int mode) {
        currentMode = mode;
        if (mode == MODE_SERVERS) {
            scrollBreadcrumbs.setVisibility(View.GONE);
            rvFiles.setVisibility(View.GONE);
            rvServers.setVisibility(View.VISIBLE);
            tvTitle.setText(R.string.smb_title);
            btnScan.setVisibility(View.VISIBLE);
            btnManual.setVisibility(View.VISIBLE);
            btnAccount.setVisibility(View.GONE);
        } else {
            scrollBreadcrumbs.setVisibility(View.VISIBLE);
            rvServers.setVisibility(View.GONE);
            rvFiles.setVisibility(View.VISIBLE);
            tvTitle.setText(activeServer != null ? activeServer.name : "文件浏览");
            btnScan.setVisibility(View.GONE);
            btnManual.setVisibility(View.GONE);
            btnAccount.setVisibility(View.VISIBLE);
        }
    }

    private void showLoading(String msg) {
        isBusy = true;
        loadingLayout.setVisibility(View.VISIBLE);
        tvLoadingText.setText(msg);
        emptyLayout.setVisibility(View.GONE);
    }

    private void hideLoading() {
        isBusy = false;
        loadingLayout.setVisibility(View.GONE);
    }

    private void setStatus(String text) {
        tvStatus.setText(text);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void showAuthDialog(String host, String user, String pass, boolean isManualAdd) {
        authOverlay.setVisibility(View.VISIBLE);
        dialogAuthTitle.setText(isManualAdd ? "手动添加共享服务器" : "登录到服务器");
        dialogAuthSubtitle.setText(host.isEmpty() ? "请输入局域网 IP 或主机名" : ("服务器: " + host));

        editServerHost.setText(host);
        editServerUser.setText(user);
        editServerPass.setText(pass);
        cbRememberCreds.setChecked(true);

        if (host.isEmpty()) {
            editServerHost.requestFocus();
        } else {
            editServerUser.requestFocus();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /* ---------------- Credentials Persistence ---------------- */

    private void saveCreds(String host, String name, String user, String pass) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        ed.putString("creds_" + host, user + "\u0000" + pass);
        ed.putString("name_" + host, name);

        String saved = sp.getString(PREF_SAVED_HOSTS, "");
        if (!saved.contains(host)) {
            saved = saved.isEmpty() ? host : (saved + "," + host);
            ed.putString(PREF_SAVED_HOSTS, saved);
        }
        ed.apply();
    }

    private String[] getSavedCreds(String host) {
        String v = getSharedPreferences(PREFS, MODE_PRIVATE).getString("creds_" + host, null);
        if (v == null) return null;
        int i = v.indexOf('\u0000');
        if (i < 0) return null;
        return new String[]{v.substring(0, i), v.substring(i + 1)};
    }

    /* ---------------- Adapters ---------------- */

    private class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            final TextView tvName, tvIp, tvAuth;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_server_name);
                tvIp = v.findViewById(R.id.tv_server_ip);
                tvAuth = v.findViewById(R.id.tv_server_auth_badge);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_smb_server, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ServerItem item = serverList.get(pos);
            h.tvName.setText(item.name);
            h.tvIp.setText(item.host);
            h.tvAuth.setText(item.hasSavedCreds ? "已保存凭据" : "免密 / 访客模式");

            h.itemView.setOnClickListener(v -> onServerClick(item));
            h.itemView.setOnLongClickListener(v -> {
                String[] creds = getSavedCreds(item.host);
                showAuthDialog(item.host, creds != null ? creds[0] : "", creds != null ? creds[1] : "", false);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return serverList.size();
        }
    }

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            final ImageView imgIcon;
            final TextView tvName, tvInfo, tvAction;

            VH(View v) {
                super(v);
                imgIcon = v.findViewById(R.id.img_file_icon);
                tvName = v.findViewById(R.id.tv_file_name);
                tvInfo = v.findViewById(R.id.tv_file_info);
                tvAction = v.findViewById(R.id.tv_file_action);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_smb_file, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            FileItem item = fileList.get(pos);
            h.tvName.setText(item.name);

            if (item.type == TYPE_SHARE || item.type == TYPE_DIR) {
                h.imgIcon.setImageResource(R.drawable.ic_smb_folder);
                h.tvInfo.setText(item.type == TYPE_SHARE ? "共享分区" : "文件夹");
                h.tvAction.setVisibility(View.GONE);
            } else if (item.type == TYPE_APK) {
                h.imgIcon.setImageResource(R.drawable.ic_smb_apk);
                h.tvInfo.setText(formatSize(item.size));
                h.tvAction.setVisibility(View.VISIBLE);
                h.tvAction.setText("点击安装");
            } else {
                h.imgIcon.setImageResource(R.drawable.ic_smb_file);
                h.tvInfo.setText(formatSize(item.size));
                h.tvAction.setVisibility(View.GONE);
            }

            h.itemView.setOnClickListener(v -> onFileItemClick(item));
        }

        @Override
        public int getItemCount() {
            return fileList.size();
        }
    }
}
