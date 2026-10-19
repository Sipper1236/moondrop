package xyz.moondrop.android;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** GitHub Releases updater. Android still presents its own package-install confirmation. */
final class AndroidUpdater {
    private static final String API="https://api.github.com/repos/Sipper1236/moondrop/releases?per_page=50";
    private static final long MAX_APK=250L*1024*1024;
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"moondrop-updater");t.setDaemon(true);return t;});
    private static volatile File pendingInstall;

    record Update(String version,String url,String sha256,String assetName) { }
    interface Callback { void complete(Update update,Exception failure); }

    void check(Callback callback) {
        WORK.execute(()->{try{callback.complete(findUpdate(),null);}catch(Exception failure){callback.complete(null,failure);}});
    }

    private Update findUpdate() throws Exception {
        JsonArray releases=JsonParser.parseString(read("https://api.github.com/repos/Sipper1236/moondrop/releases?per_page=50",1024*1024)).getAsJsonArray();
        String installed=BuildConfig.VERSION_NAME;
        for(JsonElement element:releases) {
            JsonObject release=element.getAsJsonObject();String tag=string(release,"tag_name");
            if(release.get("draft").getAsBoolean()||!tag.startsWith("android-v"))continue;
            String version=tag.substring("android-v".length());
            if(!isNewer(version,installed))continue;
            JsonArray assets=release.getAsJsonArray("assets");JsonObject apk=null,checksums=null;
            for(JsonElement value:assets){JsonObject asset=value.getAsJsonObject();String name=string(asset,"name");if(name.equals("Moondrop-"+version+"-Android.apk"))apk=asset;else if(name.equals("SHA256SUMS-Android-"+version+".txt"))checksums=asset;}
            if(apk==null)continue;
            String assetName=string(apk,"name"),digest=string(apk,"digest");
            if(digest.startsWith("sha256:"))digest=digest.substring(7);
            if(!digest.matches("(?i)[0-9a-f]{64}")) {
                if(checksums==null)continue;
                String checksumUrl=githubAsset(string(checksums,"browser_download_url"));
                digest=checksum(read(checksumUrl,32*1024),assetName);
            }
            String url=githubAsset(string(apk,"browser_download_url"));
            return new Update(version,url,digest.toLowerCase(Locale.ROOT),assetName);
        }
        return null;
    }

    void downloadAndInstall(Activity activity,Update update,Callback callback) {
        WORK.execute(()->{try {
            File directory=new File(activity.getCacheDir(),"updates");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Could not prepare update storage.");
            File target=new File(directory,"moondrop-"+update.version()+".apk");download(update.url(),target);
            if(target.length()==0||target.length()>MAX_APK||!constantTimeEquals(update.sha256(),sha256(target)))throw new IOException("The downloaded update failed its SHA-256 check.");
            verifyPackage(activity,target);
            activity.runOnUiThread(()->{pendingInstall=target;callback.complete(update,null);});
        }catch(Exception failure){activity.runOnUiThread(()->callback.complete(null,failure));}});
    }

    void install(Activity activity) throws Exception {
        File apk=pendingInstall;if(apk==null||!apk.isFile())throw new IOException("Download the update again before installing it.");
        if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()) {
            Intent settings=new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName()));
            activity.startActivityForResult(settings,7341);return;
        }
        Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".updates",apk);
        Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(install);
    }

    void resumeInstall(Activity activity) {
        if(Build.VERSION.SDK_INT<26||activity.getPackageManager().canRequestPackageInstalls())try{install(activity);}catch(Exception failure){android.widget.Toast.makeText(activity,failure.getMessage(),android.widget.Toast.LENGTH_LONG).show();}
        else android.widget.Toast.makeText(activity,"Allow installs from Moondrop, then tap Check for updates again.",android.widget.Toast.LENGTH_LONG).show();
    }

    static boolean isNewer(String candidate,String current) {
        try{int[] a=parts(candidate),b=parts(current);for(int i=0;i<3;i++)if(a[i]!=b[i])return a[i]>b[i];return false;}catch(RuntimeException invalid){return false;}
    }
    private static int[] parts(String version){String[] raw=version.split("\\.",-1);if(raw.length!=3)throw new IllegalArgumentException();return new int[]{Integer.parseInt(raw[0]),Integer.parseInt(raw[1]),Integer.parseInt(raw[2])};}
    static String checksum(String list,String file) throws IOException {
        for(String line:list.split("\\R")){String[] fields=line.trim().split("\\s+",2);if(fields.length==2&&fields[1].replaceFirst("^\\*","").equals(file)&&fields[0].matches("(?i)[0-9a-f]{64}"))return fields[0].toLowerCase(Locale.ROOT);}
        throw new IOException("The release does not include a valid checksum for its Android APK.");
    }
    private static String githubAsset(String address) throws IOException {try{URL url=URI.create(address).toURL();if(!"https".equalsIgnoreCase(url.getProtocol())||!"github.com".equalsIgnoreCase(url.getHost()))throw new IOException("The release contains an unexpected download address.");return address;}catch(IllegalArgumentException invalid){throw new IOException("The release contains an invalid download address.",invalid);}}
    private static String string(JsonObject object,String key){return object.has(key)&&!object.get(key).isJsonNull()?object.get(key).getAsString():"";}
    private static String read(String address,int max) throws IOException {
        URL url=URI.create(address).toURL();if(!"https".equalsIgnoreCase(url.getProtocol()))throw new IOException("Update checks require HTTPS.");
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","Moondrop-Android");connection.setRequestProperty("Accept","application/vnd.github+json");
        try{if(connection.getResponseCode()!=200)throw new IOException("GitHub Releases is unavailable.");try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>max)throw new IOException("The update response is too large.");output.write(buffer,0,n);}return output.toString(StandardCharsets.UTF_8.name());}}finally{connection.disconnect();}
    }
    private static void download(String address,File target) throws IOException {
        URL url=URI.create(address).toURL();HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(true);connection.setRequestProperty("User-Agent","Moondrop-Android");
        File temp=new File(target.getParentFile(),target.getName()+".part");
        try{if(connection.getResponseCode()!=200||!"https".equalsIgnoreCase(connection.getURL().getProtocol()))throw new IOException("The Android update could not be downloaded securely.");try(InputStream input=connection.getInputStream();OutputStream output=Files.newOutputStream(temp.toPath())){byte[] buffer=new byte[65536];int n;long total=0;while((n=input.read(buffer))!=-1){if((total+=n)>MAX_APK)throw new IOException("The Android update is larger than expected.");output.write(buffer,0,n);}}if(!temp.renameTo(target))throw new IOException("Could not stage the verified Android update.");}finally{connection.disconnect();if(temp.exists())temp.delete();}
    }
    private static String sha256(File file) throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(file.toPath())){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}return hex(digest.digest());}
    private static boolean constantTimeEquals(String a,String b){return MessageDigest.isEqual(a.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),b.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));}
    private static void verifyPackage(Activity activity,File apk) throws Exception {
        PackageManager manager=activity.getPackageManager();PackageInfo archive=manager.getPackageArchiveInfo(apk.getAbsolutePath(),Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
        if(archive==null||!activity.getPackageName().equals(archive.packageName))throw new IOException("The downloaded APK is not a Moondrop update.");
        PackageInfo installed=Build.VERSION.SDK_INT>=33?manager.getPackageInfo(activity.getPackageName(),PackageManager.PackageInfoFlags.of(Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES)):manager.getPackageInfo(activity.getPackageName(),Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
        Set<String> oldSigners=signers(installed),newSigners=signers(archive);if(oldSigners.isEmpty()||!oldSigners.equals(newSigners))throw new IOException("The update APK is not signed by the installed Moondrop publisher key.");
        long current=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;long incoming=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode;if(incoming<=current)throw new IOException("This update is not newer than the installed app.");
    }
    private static Set<String> signers(PackageInfo info) throws Exception {
        android.content.pm.Signature[] values;if(Build.VERSION.SDK_INT>=28&&info.signingInfo!=null)values=info.signingInfo.getApkContentsSigners();else values=info.signatures;
        if(values==null)return Set.of();Set<String> result=new TreeSet<>();MessageDigest digest=MessageDigest.getInstance("SHA-256");for(android.content.pm.Signature signature:values)result.add(hex(digest.digest(signature.toByteArray())));return result;
    }
    private static String hex(byte[] bytes){char[] digits="0123456789abcdef".toCharArray(),result=new char[bytes.length*2];for(int i=0;i<bytes.length;i++){int value=bytes[i]&255;result[i*2]=digits[value>>>4];result[i*2+1]=digits[value&15];}return new String(result);}
}
