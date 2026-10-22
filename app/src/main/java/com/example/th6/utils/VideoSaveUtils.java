package com.example.th6.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.IntentSender;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.RgbAdjustment;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VideoSaveUtils {
 private static final ExecutorService STORAGE_EXECUTOR = Executors.newSingleThreadExecutor();
 private static final String RECOVERY_BACKUP = "video_overwrite_recovery.tmp";
 private static final String RECOVERY_JOURNAL = "video_overwrite_recovery.uri";
 public interface SaveVideoCallback {
  void onSuccess(Uri savedUri,String name);
  void onError(String error);
  default void onPermissionRequired(IntentSender sender) { onError("Android yêu cầu quyền ghi cho video này."); }
 }
 public interface RecoveryCallback { void onComplete(String error); }
 private VideoSaveUtils(){}
 /** Maps the editor's displayed contrast multiplier (0.5x..2x) to Media3's contrast range. */
 public static float contrastSliderToValue(int sliderValue){
  float factor=Math.max(.5f,Math.min(2f,.5f+sliderValue/100f));
  return (1.0001f*factor-1f)/(factor+1f);
 }
 public static List<Effect> createEffects(int brightness,int contrast,int saturation,int exposure,int highlights,int shadows,int temperature,int filter){
  List<Effect> effects=new ArrayList<>();
  float b=brightness/100f; if(Math.abs(b)>.005f)effects.add(new Brightness(b));
  float c=contrastSliderToValue(contrast); if(Math.abs(c)>.005f)effects.add(new Contrast(c));
  int filterSaturation=filter==1?25:filter==5?10:0; int sat=(int)Math.max(-100,Math.min(100,(saturation/100f-1f)*100+filterSaturation));
  if(filter==4)effects.add(new HslAdjustment.Builder().adjustSaturation(-100).build());
  else if(sat!=0)effects.add(new HslAdjustment.Builder().adjustSaturation(sat).build());
  float ev=exposure/10f; float scale=(float)Math.pow(2d,ev); float temp=temperature/100f;
  float red=scale*(1f+Math.max(0,temp)*.25f), blue=scale*(1f+Math.max(0,-temp)*.25f);
  if(Math.abs(ev)>.005f||Math.abs(temp)>.005f)effects.add(new RgbAdjustment.Builder().setRedScale(red).setGreenScale(scale).setBlueScale(blue).build());
  if(filter==2)effects.add(new RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(1.01f).setBlueScale(.94f).build());
  if(filter==3)effects.add(new RgbAdjustment.Builder().setRedScale(.94f).setGreenScale(1.01f).setBlueScale(1.08f).build());
  if(filter==5)effects.add(new Contrast(.35f));
  if(filter==6)effects.add(new androidx.media3.effect.GaussianBlur(3f));
  if(highlights!=0||shadows!=0){
   final int n=17; int[][][] cube=new int[n][n][n]; float h=highlights/100f*.30f, sh=shadows/100f*.30f;
   for(int r=0;r<n;r++)for(int g=0;g<n;g++)for(int bi=0;bi<n;bi++){float rf=r/(float)(n-1),gf=g/(float)(n-1),bf=bi/(float)(n-1);cube[r][g][bi]=0xff000000|(channel(rf,h,sh)<<16)|(channel(gf,h,sh)<<8)|channel(bf,h,sh);}
   effects.add(androidx.media3.effect.SingleColorLut.createFromCube(cube));
  }
  return effects;
 }
 private static int channel(float value,float highlights,float shadows){float v=value+highlights*v2(value)+shadows*v2(1f-value);return Math.round(Math.max(0f,Math.min(1f,v))*255f);}
 private static float v2(float value){return value*value;}
 public static void trimAndExportVideo(Context context,Uri source,long startMs,long endMs,boolean mute,SaveVideoCallback callback){
  trimAndExportVideo(context,source,startMs,endMs,mute,Collections.emptyList(),callback);
 }
 public static void trimAndExportVideo(Context context,Uri source,long startMs,long endMs,boolean mute,List<Effect> effects,SaveVideoCallback callback){
  Context app=context.getApplicationContext(); Handler main=new Handler(Looper.getMainLooper());
  try {
   String filename="PhotoVault_Video_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.getDefault()).format(new Date())+".mp4";
   File temp=new File(app.getCacheDir(),filename);
   MediaItem input=new MediaItem.Builder().setUri(source).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build();
   EditedMediaItem edited=new EditedMediaItem.Builder(input).setRemoveAudio(mute).setEffects(new Effects(Collections.emptyList(),effects)).build();
   Transformer transformer=new Transformer.Builder(app).addListener(new Transformer.Listener(){
    @Override public void onCompleted(androidx.media3.transformer.Composition composition,ExportResult result){
     Uri outUri=null; boolean success=false;
     try {ContentResolver resolver=app.getContentResolver();ContentValues values=new ContentValues();values.put(MediaStore.Video.Media.DISPLAY_NAME,filename);values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");if(Build.VERSION.SDK_INT>=29){values.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/PhotoVault");values.put(MediaStore.Video.Media.IS_PENDING,1);}outUri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);if(outUri==null)throw new java.io.IOException("Không thể tạo video trong thư viện.");try(FileInputStream in=new FileInputStream(temp);OutputStream out=resolver.openOutputStream(outUri,"w")){if(out==null)throw new java.io.IOException("Không thể ghi video vào thư viện.");byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);out.flush();}if(Build.VERSION.SDK_INT>=29){ContentValues done=new ContentValues();done.put(MediaStore.Video.Media.IS_PENDING,0);resolver.update(outUri,done,null,null);}success=true;}catch(Exception e){if(outUri!=null)try{app.getContentResolver().delete(outUri,null,null);}catch(Exception ignored){} final String error=e.getMessage();main.post(()->callback.onError("Không thể lưu video: "+error));}finally{if(temp.exists())temp.delete();}if(success){Uri resultUri=outUri;main.post(()->callback.onSuccess(resultUri,filename));}
    }
    @Override public void onError(androidx.media3.transformer.Composition composition,ExportResult result,ExportException exception){if(temp.exists())temp.delete();main.post(()->callback.onError("Xuất video thất bại: "+exception.getMessage()));}
   }).build();
   transformer.start(edited,temp.getAbsolutePath());
  }catch(Exception e){main.post(()->callback.onError("Không thể bắt đầu xuất video: "+e.getMessage()));}
 }

 /** Preflights write access without changing the media. On Android 10, opening in append mode
  * produces Android's recoverable-consent exception for items owned by another app. */
 public static IntentSender getWriteConsentRequest(Context context, Uri target) throws Exception {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
   return MediaStore.createWriteRequest(context.getContentResolver(), Collections.singletonList(target))
           .getIntentSender();
  }
  if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
   try (android.os.ParcelFileDescriptor ignored = context.getContentResolver().openFileDescriptor(target, "wa")) {
    if (ignored == null) throw new java.io.IOException("Không mở được video để ghi.");
    return null;
   } catch (android.app.RecoverableSecurityException recoverable) {
    return recoverable.getUserAction().getActionIntent().getIntentSender();
   }
  }
  return null;
 }

 /** Exports and validates a replacement before touching the original MediaStore item. */
 public static void overwriteVideo(Context context, Uri source, long originalDateModified,
                                   long startMs, long endMs,
                                   boolean mute, List<Effect> effects, SaveVideoCallback callback) {
  Context app = context.getApplicationContext();
  Handler main = new Handler(Looper.getMainLooper());
  String name = "PhotoVault_Overwrite_" + System.currentTimeMillis() + ".mp4";
  File rendered = new File(app.getCacheDir(), name);
  File backup = new File(app.getNoBackupFilesDir(), "video_overwrite_recovery.tmp");
  try {
   MediaItem input = new MediaItem.Builder().setUri(source)
           .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                   .setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build();
   EditedMediaItem edited = new EditedMediaItem.Builder(input).setRemoveAudio(mute)
           .setEffects(new Effects(Collections.emptyList(), effects)).build();
   Transformer transformer = new Transformer.Builder(app).addListener(new Transformer.Listener() {
    @Override public void onCompleted(androidx.media3.transformer.Composition composition, ExportResult result) {
     STORAGE_EXECUTOR.execute(() -> {
     try {
      if (!isUsableVideo(rendered)) throw new java.io.IOException("Video xuất ra không hợp lệ.");
      copyUriToFile(app, source, backup);
      if (backup.length() == 0L) throw new java.io.IOException("Không tạo được bản khôi phục video gốc.");
      long originalSize = getMediaSize(app, source);
      if (originalSize > 0L && backup.length() != originalSize)
       throw new java.io.IOException("Bản sao khôi phục không khớp kích thước video gốc.");
      writeRecoveryJournal(app, source, originalDateModified);
      try {
       copyFileToUri(app, rendered, source);
       if (!isUsableVideo(app, source)) throw new java.io.IOException("Không xác minh được video sau khi ghi.");
       ContentValues metadata = new ContentValues();
       metadata.put(MediaStore.Video.Media.DATE_MODIFIED,
               Math.max(System.currentTimeMillis() / 1000L, originalDateModified + 1L));
       int updated = app.getContentResolver().update(source, metadata, null, null);
       if (updated <= 0) throw new java.io.IOException("Không cập nhật được metadata MediaStore.");
       long savedSize = getMediaSize(app, source);
       if (savedSize > 0L && savedSize != rendered.length())
        throw new java.io.IOException("Kích thước video sau ghi không khớp file đã xử lý.");
       app.getContentResolver().notifyChange(source, null);
      } catch (Exception writeFailure) {
       try {
        copyFileToUri(app, backup, source);
        if (!isUsableVideo(app, source)) throw new java.io.IOException("Bản gốc khôi phục không hợp lệ.");
        restoreDateModified(app, source, originalDateModified);
        app.getContentResolver().notifyChange(source, null);
        clearRecoveryFiles(app, backup);
        throw new java.io.IOException("Không lưu đè được video; bản gốc đã được khôi phục.", writeFailure);
       } catch (Exception rollbackFailure) {
        if (rollbackFailure.getCause() == writeFailure) throw rollbackFailure;
        throw new java.io.IOException("Lỗi khi lưu đè và không thể xác minh khôi phục. Bản sao cứu hộ được giữ tại "
                + backup.getAbsolutePath(), writeFailure);
       }
      }
      clearRecoveryFiles(app, backup);
      rendered.delete();
      main.post(() -> callback.onSuccess(source, name));
     } catch (Exception error) {
      rendered.delete();
      main.post(() -> callback.onError(error.getMessage() == null ? "Không lưu đè được video." : error.getMessage()));
     }
     });
    }
    @Override public void onError(androidx.media3.transformer.Composition composition, ExportResult result,
                                  ExportException exception) {
     rendered.delete();
     main.post(() -> callback.onError("Xuất video thất bại: " + exception.getMessage()));
    }
   }).build();
   transformer.start(edited, rendered.getAbsolutePath());
  } catch (Exception error) {
   rendered.delete();
   main.post(() -> callback.onError("Không thể bắt đầu lưu đè: " + error.getMessage()));
  }
 }

 private static void copyUriToFile(Context context, Uri source, File target) throws Exception {
  try (InputStream in = context.getContentResolver().openInputStream(source);
       OutputStream out = new FileOutputStream(target, false)) {
   if (in == null) throw new java.io.IOException("Không đọc được video gốc.");
   copy(in, out);
  }
 }

 private static void copyFileToUri(Context context, File source, Uri target) throws Exception {
  try (InputStream in = new FileInputStream(source);
       OutputStream out = context.getContentResolver().openOutputStream(target, "rwt")) {
   if (out == null) throw new java.io.IOException("Không mở được video đích để ghi.");
   copy(in, out);
  }
 }

 private static void copy(InputStream in, OutputStream out) throws Exception {
  byte[] buffer = new byte[64 * 1024];
  int read;
  while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
  out.flush();
 }

 /** Restores a staged original before normal navigation if Android killed the app mid-write. */
 public static boolean hasPendingOverwriteRecovery(Context context) {
  return new File(context.getNoBackupFilesDir(), RECOVERY_JOURNAL).isFile();
 }

 public static void recoverInterruptedOverwriteAsync(Context context, RecoveryCallback callback) {
  Context app = context.getApplicationContext();
  Handler main = new Handler(Looper.getMainLooper());
  STORAGE_EXECUTOR.execute(() -> {
   String error = recoverInterruptedOverwrite(app);
   main.post(() -> callback.onComplete(error));
  });
 }

 public static String recoverInterruptedOverwrite(Context context) {
  File backup = new File(context.getNoBackupFilesDir(), RECOVERY_BACKUP);
  File journal = new File(context.getNoBackupFilesDir(), RECOVERY_JOURNAL);
  if (!journal.isFile()) return null;
  try (BufferedReader reader = new BufferedReader(new FileReader(journal))) {
   String uriText = reader.readLine();
   String dateModifiedText = reader.readLine();
   if (uriText == null || uriText.isEmpty() || !backup.isFile() || backup.length() == 0L)
    throw new java.io.IOException("Bản gốc khôi phục không đầy đủ.");
   Uri target = Uri.parse(uriText);
   copyFileToUri(context, backup, target);
   if (!isUsableVideo(context, target)) throw new java.io.IOException("Không xác minh được video đã khôi phục.");
   if (dateModifiedText != null && !dateModifiedText.isEmpty()) {
    try { restoreDateModified(context, target, Long.parseLong(dateModifiedText)); }
    catch (NumberFormatException ignored) { }
   }
   context.getContentResolver().notifyChange(target, null);
   clearRecoveryFiles(context, backup);
   return null;
  } catch (Exception error) {
   return "Không khôi phục tự động được video sau khi ứng dụng bị dừng: " + error.getMessage()
           + ". Bản sao cứu hộ vẫn được giữ trong dữ liệu riêng của PhotoVault tại " + backup.getAbsolutePath();
  }
 }

 private static void writeRecoveryJournal(Context context, Uri source, long originalDateModified) throws Exception {
  File journal = new File(context.getNoBackupFilesDir(), RECOVERY_JOURNAL);
  try (FileOutputStream out = new FileOutputStream(journal, false)) {
   out.write((source.toString() + "\n" + originalDateModified)
           .getBytes(java.nio.charset.StandardCharsets.UTF_8));
   out.getFD().sync();
  }
 }

 private static void restoreDateModified(Context context, Uri source, long dateModified) {
  if (dateModified <= 0L) return;
  ContentValues values = new ContentValues();
  values.put(MediaStore.Video.Media.DATE_MODIFIED, dateModified);
  context.getContentResolver().update(source, values, null, null);
 }

 private static long getMediaSize(Context context, Uri uri) {
  try (android.database.Cursor cursor = context.getContentResolver().query(uri,
          new String[]{MediaStore.Video.Media.SIZE}, null, null, null)) {
   return cursor != null && cursor.moveToFirst() ? cursor.getLong(0) : -1L;
  } catch (Exception ignored) { return -1L; }
 }

 private static void clearRecoveryFiles(Context context, File backup) {
  new File(context.getNoBackupFilesDir(), RECOVERY_JOURNAL).delete();
  backup.delete();
 }

 private static boolean isUsableVideo(File file) {
  if (!file.isFile() || file.length() <= 0L) return false;
  MediaMetadataRetriever retriever = new MediaMetadataRetriever();
  try {
   retriever.setDataSource(file.getAbsolutePath());
   String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
   return duration != null && Long.parseLong(duration) > 0L;
  } catch (Exception error) { return false; }
  finally { try { retriever.release(); } catch (Exception ignored) { } }
 }

 private static boolean isUsableVideo(Context context, Uri uri) {
  MediaMetadataRetriever retriever = new MediaMetadataRetriever();
  try {
   retriever.setDataSource(context, uri);
   String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
   return duration != null && Long.parseLong(duration) > 0L;
  } catch (Exception error) { return false; }
  finally { try { retriever.release(); } catch (Exception ignored) { } }
 }
}
