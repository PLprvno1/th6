package com.example.th6.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import com.example.th6.adapters.AlbumMediaAdapter;
import com.example.th6.data.AuthRepository;
import com.example.th6.data.LocalAlbumStore;
import com.example.th6.data.PhotoRepository;
import com.example.th6.databinding.ActivityAlbumMediaPickerBinding;
import com.example.th6.models.AlbumMediaEntry;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.services.DevicePhotoScanner;
import com.example.th6.utils.PhotoVaultTrashCache;
import io.github.jan.supabase.auth.user.UserInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AlbumMediaPickerActivity extends AppCompatActivity implements AlbumMediaAdapter.Listener {
    public static final String EXTRA_ALBUM_ID = "album_id";
    public static final String EXTRA_SELECTED = "selected_album_media";
    private ActivityAlbumMediaPickerBinding binding;
    private AlbumMediaAdapter adapter;
    private final List<AlbumMediaEntry> entries = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private String albumId, uid;
    private List<LocalMediaPhoto> localItems;
    private List<Photo> cloudItems;
    private boolean localReady, cloudReady;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding=ActivityAlbumMediaPickerBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        albumId=getIntent().getStringExtra(EXTRA_ALBUM_ID);
        UserInfo user=new AuthRepository().getCurrentUser();
        if(user==null || albumId==null){ setResult(RESULT_CANCELED); finish(); return; }
        uid=user.getId();
        adapter=new AlbumMediaAdapter(this,this); adapter.setSelectionMode(true); adapter.setShowAlreadyStatus(true); binding.rvPickerMedia.setLayoutManager(new GridLayoutManager(this,3)); binding.rvPickerMedia.setAdapter(adapter);
        binding.btnPickerBack.setOnClickListener(v->finish());
        binding.btnConfirmAdd.setOnClickListener(v->confirmSelection());
        binding.pickerProgress.setVisibility(View.VISIBLE);
        new DevicePhotoScanner().scanPhotos(this,new DevicePhotoScanner.ScanCallback(){
            @Override public void onScanComplete(List<LocalMediaPhoto> photos){ localItems=photos; localReady=true; renderWhenReady(); }
            @Override public void onScanError(String error){ localItems=new ArrayList<>(); localReady=true; Toast.makeText(AlbumMediaPickerActivity.this,"Không đọc được một phần thư viện thiết bị: "+error,Toast.LENGTH_LONG).show(); renderWhenReady(); }
        });
        new PhotoRepository().getPhotosByUserAsync(uid,new PhotoRepository.Callback<List<Photo>>(){
            @Override public void onSuccess(List<Photo> photos){ cloudItems=photos==null?new ArrayList<>():photos; cloudReady=true; renderWhenReady(); }
            @Override public void onError(String error){ cloudItems=new ArrayList<>(); cloudReady=true; Toast.makeText(AlbumMediaPickerActivity.this,"Không tải được media cloud: "+error,Toast.LENGTH_LONG).show(); renderWhenReady(); }
        });
    }

    private void renderWhenReady(){
        if(!localReady || !cloudReady || isFinishing()) return;
        UserInfo current=new AuthRepository().getCurrentUser();
        if(current==null || !uid.equals(current.getId())){ setResult(RESULT_CANCELED); finish(); return; }
        Map<String,LocalMediaPhoto> localsByUri=new HashMap<>();
        for(LocalMediaPhoto local:localItems) localsByUri.put(local.getContentUri(),local);
        Set<String> consumed=new HashSet<>();
        Set<String> localTrashIdentities=PhotoVaultTrashCache.getLocalMediaIdentityKeys(this);
        entries.clear();
        LocalAlbumStore store=new LocalAlbumStore(this);
        try {
            Set<String> localMemberships=new HashSet<>(store.getMembers(uid,albumId));
            for(Photo cloud:cloudItems){
                if(cloud.isTrashed()) continue;
                String uri=cloud.getLocalMediaUri();
                LocalMediaPhoto local=uri==null?null:localsByUri.get(uri);
                if(local!=null){ consumed.add(uri); local.setCloudPhoto(cloud); local.setCloudPhotoId(cloud.getId()); local.setCloudUrl(cloud.getImageUrl()); }
                else {
                    local=new LocalMediaPhoto(); local.setContentUri(""); local.setDisplayName(cloud.getName()); local.setCloudPhoto(cloud); local.setCloudPhotoId(cloud.getId()); local.setCloudUrl(cloud.getImageUrl()); local.setVideo("video".equalsIgnoreCase(cloud.getMediaType()));
                }
                boolean member=cloud.getAlbumIds()!=null && cloud.getAlbumIds().contains(albumId);
                // Keep local-only membership visible as already selected while it is
                // being reconciled with a cloud-backed photo row.
                if (!member && local != null) member=localMemberships.contains(localKey(local));
                String key="cloud:"+cloud.getId();
                entries.add(new AlbumMediaEntry(key,local,cloud,member));
            }
            for(LocalMediaPhoto local:localItems){
                if(consumed.contains(local.getContentUri())) continue;
                if(PhotoVaultTrashCache.isLocalMediaTrashed(localTrashIdentities,local)) continue;
                String key=localKey(local);
                boolean member=localMemberships.contains(key);
                entries.add(new AlbumMediaEntry(key,local,null,member));
            }
        } finally { store.close(); }
        Collections.sort(entries, Comparator.comparingLong(AlbumMediaEntry::getTimestamp).reversed().thenComparing(AlbumMediaEntry::getKey));
        adapter.setItems(entries); binding.pickerProgress.setVisibility(View.GONE); binding.tvPickerEmpty.setVisibility(entries.isEmpty()?View.VISIBLE:View.GONE); updateCount();
    }
    public static String localKey(LocalMediaPhoto local){return "media:"+(local.isVideo()?"video":"image")+":"+local.getMediaId()+":"+local.getDateModified()+":"+local.getSizeBytes();}
    private void updateCount(){ binding.tvPickerCount.setText(selected.size()==0?"Chọn ảnh và video":"Đã chọn "+selected.size()+" mục"); binding.btnConfirmAdd.setEnabled(!selected.isEmpty()); }
    @Override public void onClick(AlbumMediaEntry entry){
        if(entry.isAlreadyInAlbum()) return;
        entry.setSelected(!entry.isSelected()); if(entry.isSelected()) selected.add(entry.getKey()); else selected.remove(entry.getKey());
        adapter.notifyItemChanged(entries.indexOf(entry)); updateCount();
    }
    @Override public void onLongClick(AlbumMediaEntry entry,View anchor){onClick(entry);}
    private void confirmSelection(){
        ArrayList<AlbumMediaEntry> chosen=new ArrayList<>();
        for(AlbumMediaEntry entry:entries) if(selected.contains(entry.getKey())) chosen.add(entry);
        if(chosen.isEmpty()) return;
        Intent result=new Intent(); result.putExtra(EXTRA_SELECTED,chosen); setResult(RESULT_OK,result); finish();
    }
}
