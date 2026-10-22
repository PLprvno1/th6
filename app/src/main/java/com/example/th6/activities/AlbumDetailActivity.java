package com.example.th6.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.GridLayoutManager;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.load.engine.GlideException;
import com.example.th6.R;
import com.example.th6.adapters.AlbumMediaAdapter;
import com.example.th6.data.AuthRepository;
import com.example.th6.data.LocalAlbumStore;
import com.example.th6.data.PhotoRepository;
import com.example.th6.databinding.ActivityAlbumDetailBinding;
import com.example.th6.models.Album;
import com.example.th6.models.AlbumMediaEntry;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.services.DevicePhotoScanner;
import com.example.th6.utils.PhotoVaultTrashCache;
import com.example.th6.utils.MediaTimelineUtils;
import com.example.th6.utils.PhotoTimelineUtils;
import com.example.th6.utils.TimelineSortMenu;
import io.github.jan.supabase.auth.user.UserInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AlbumDetailActivity extends AppCompatActivity implements AlbumMediaAdapter.Listener {
    private static final int REQUEST_PICK_MEDIA = 4201;
    private static final int ACTION_SET_COVER = 1;
    private static final int ACTION_REMOVE = 2;
    private ActivityAlbumDetailBinding binding;
    private Album album;
    private AlbumMediaAdapter adapter;
    private PhotoRepository repository;
    private AuthRepository authRepository;
    private LocalAlbumStore localStore;
    private List<AlbumMediaEntry> albumMedia = new ArrayList<>();
    private List<LocalMediaPhoto> scanResult;
    private List<Photo> cloudResult;
    private boolean scanReady, cloudReady, cloudLoadFailed, selectionMode, applying, firstResume = true;
    private int currentViewMode = PhotoTimelineUtils.VIEW_MODE_ALL;
    private int currentMediaFilter = MediaTimelineUtils.FILTER_ALL;
    private int currentSortMode = R.id.sort_date_desc;
    private int loadGeneration;
    private String uid;
    private final Set<String> selected = new HashSet<>();
    private final Set<String> unavailableCoverKeys = new HashSet<>();

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding=ActivityAlbumDetailBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        authRepository=new AuthRepository(); repository=new PhotoRepository(); localStore=new LocalAlbumStore(this);
        album=(Album)getIntent().getSerializableExtra("album");
        UserInfo user=authRepository.getCurrentUser();
        if(album==null || user==null || !album.getOwnerUid().equals(user.getId())) { Toast.makeText(this,"Không tìm thấy album của tài khoản hiện tại",Toast.LENGTH_SHORT).show(); finish(); return; }
        uid=user.getId(); binding.tvAlbumTitle.setText(album.getName());
        adapter=new AlbumMediaAdapter(this,this);
        GridLayoutManager albumGrid=new GridLayoutManager(this,3);
        albumGrid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup(){@Override public int getSpanSize(int position){return adapter.getItemViewType(position)==1?3:1;}});
        binding.rvAlbumPhotos.setLayoutManager(albumGrid); binding.rvAlbumPhotos.setAdapter(adapter);
        binding.btnBack.setOnClickListener(v->finish()); binding.swipeRefresh.setOnRefreshListener(this::loadAlbumPhotos);
        binding.btnEditAlbum.setOnClickListener(v->showEditAlbumNameDialog()); binding.btnDeleteAlbum.setOnClickListener(v->confirmDeleteAlbum());
        binding.btnAddAlbumMedia.setOnClickListener(v->openPicker()); binding.btnSelectAlbumMedia.setOnClickListener(v->toggleSelectionMode());
        binding.btnCancelAlbumSelection.setOnClickListener(v->exitSelectionMode()); binding.btnRemoveAlbumSelection.setOnClickListener(v->confirmRemoveSelected());
        currentSortMode=getSharedPreferences("photovault_library",MODE_PRIVATE).getInt("sort_mode",R.id.sort_date_desc);
        currentViewMode=getSharedPreferences("photovault_library",MODE_PRIVATE).getInt("view_mode",PhotoTimelineUtils.VIEW_MODE_ALL);
        currentMediaFilter=getSharedPreferences("photovault_library",MODE_PRIVATE).getInt("media_filter",MediaTimelineUtils.FILTER_ALL);
        setCheckedChip(currentViewMode,currentMediaFilter);
        binding.chipAlbumAllTime.setOnClickListener(v->selectTimeline(PhotoTimelineUtils.VIEW_MODE_ALL,currentMediaFilter));
        binding.chipAlbumDays.setOnClickListener(v->selectTimeline(PhotoTimelineUtils.VIEW_MODE_DAYS,currentMediaFilter));
        binding.chipAlbumMonths.setOnClickListener(v->selectTimeline(PhotoTimelineUtils.VIEW_MODE_MONTHS,currentMediaFilter));
        binding.chipAlbumYears.setOnClickListener(v->selectTimeline(PhotoTimelineUtils.VIEW_MODE_YEARS,currentMediaFilter));
        binding.chipAlbumMediaAll.setOnClickListener(v->selectTimeline(currentViewMode,MediaTimelineUtils.FILTER_ALL));
        binding.chipAlbumImages.setOnClickListener(v->selectTimeline(currentViewMode,MediaTimelineUtils.FILTER_IMAGES));
        binding.chipAlbumVideos.setOnClickListener(v->selectTimeline(currentViewMode,MediaTimelineUtils.FILTER_VIDEOS));
        binding.btnAlbumSort.setOnClickListener(v->TimelineSortMenu.show(this,v,currentSortMode,sort->{currentSortMode=sort;getSharedPreferences("photovault_library",MODE_PRIVATE).edit().putInt("sort_mode",sort).apply();applyTimelineDisplay();}));
        loadAlbumPhotos();
    }

    private void loadAlbumPhotos(){
        if(isFinishing()) return;
        UserInfo current=authRepository.getCurrentUser(); if(current==null || !uid.equals(current.getId())) { finish(); return; }
        final int generation=++loadGeneration;
        scanReady=false; cloudReady=false; cloudLoadFailed=false; binding.progressBar.setVisibility(View.VISIBLE); binding.swipeRefresh.setRefreshing(false);
        new DevicePhotoScanner().scanPhotos(this,new DevicePhotoScanner.ScanCallback(){
            @Override public void onScanComplete(List<LocalMediaPhoto> result){ if(!isCurrentLoad(generation))return; scanResult=result; scanReady=true; composeAlbum(generation); }
            @Override public void onScanError(String error){ if(!isCurrentLoad(generation))return; scanResult=new ArrayList<>(); scanReady=true; Toast.makeText(AlbumDetailActivity.this,"Không tải được media trên thiết bị: "+error,Toast.LENGTH_LONG).show(); composeAlbum(generation); }
        });
        repository.getPhotosByUserAsync(uid,new PhotoRepository.Callback<List<Photo>>(){
            @Override public void onSuccess(List<Photo> result){ if(!isCurrentLoad(generation))return; cloudResult=result==null?new ArrayList<>():result; cloudReady=true; composeAlbum(generation); }
            @Override public void onError(String error){ if(!isCurrentLoad(generation))return; if(cloudResult==null)cloudResult=new ArrayList<>(); cloudLoadFailed=true; cloudReady=true; Toast.makeText(AlbumDetailActivity.this,"Không tải được media cloud: "+error,Toast.LENGTH_LONG).show(); composeAlbum(generation); }
        });
    }

    private boolean isCurrentLoad(int generation){ UserInfo current=authRepository.getCurrentUser(); return binding!=null && !isFinishing() && generation==loadGeneration && current!=null && uid.equals(current.getId()); }

    private void composeAlbum(int generation){
        if(!scanReady || !cloudReady || binding==null || isFinishing()) return;
        UserInfo current=authRepository.getCurrentUser(); if(current==null || !uid.equals(current.getId())) { finish(); return; }
        Map<String,LocalMediaPhoto> localByUri=new HashMap<>();
        for(LocalMediaPhoto item:scanResult) localByUri.put(item.getContentUri(),item);
        Set<String> consumed=new HashSet<>(); List<AlbumMediaEntry> result=new ArrayList<>();
        Set<String> localMemberships=new HashSet<>(localStore.getMembers(uid,album.getId()));
        Set<String> localTrashIdentities=PhotoVaultTrashCache.getLocalMediaIdentityKeys(this);
        List<Photo> orderedCloud=new ArrayList<>(cloudResult);
        orderedCloud.sort(Comparator.comparingLong(Photo::getCreatedAt).thenComparing(Photo::getId));
        for(Photo cloud:orderedCloud){
            LocalMediaPhoto local=cloud.getLocalMediaUri()==null?null:localByUri.get(cloud.getLocalMediaUri());
            if(cloud.isTrashed()) {
                if(local!=null) consumed.add(local.getContentUri());
                continue;
            }
            boolean cloudMember=cloud.getAlbumIds()!=null && cloud.getAlbumIds().contains(album.getId());
            boolean localMember=local!=null && localMemberships.contains(AlbumMediaPickerActivity.localKey(local));
            if(local!=null){ consumed.add(local.getContentUri()); local.setCloudPhoto(cloud); local.setCloudPhotoId(cloud.getId()); local.setCloudUrl(cloud.getImageUrl()); }
            else { local=new LocalMediaPhoto(); local.setContentUri(""); local.setDisplayName(cloud.getName()); local.setCloudPhoto(cloud); local.setCloudPhotoId(cloud.getId()); local.setCloudUrl(cloud.getImageUrl()); local.setVideo("video".equalsIgnoreCase(cloud.getMediaType())); }
            if(cloudMember || localMember) result.add(new AlbumMediaEntry("cloud:"+cloud.getId(),local,cloud,true));
            if(localMember && !cloudMember) migrateLocalMembership(local,cloud);
        }
        for(LocalMediaPhoto local:scanResult){
            if(consumed.contains(local.getContentUri())) continue;
            if(PhotoVaultTrashCache.isLocalMediaTrashed(localTrashIdentities,local)) continue;
            String key=AlbumMediaPickerActivity.localKey(local);
            if(localMemberships.contains(key)) result.add(new AlbumMediaEntry(key,local,null,true));
        }
        result.sort(Comparator.comparingLong(AlbumMediaEntry::getTimestamp).reversed().thenComparing(AlbumMediaEntry::getKey));
        albumMedia=result; unavailableCoverKeys.clear(); binding.progressBar.setVisibility(View.GONE); binding.swipeRefresh.setRefreshing(false);
        binding.layoutEmpty.setVisibility(albumMedia.isEmpty()?View.VISIBLE:View.GONE); binding.rvAlbumPhotos.setVisibility(albumMedia.isEmpty()?View.GONE:View.VISIBLE);
        binding.tvAlbumMediaCount.setText(albumMedia.size()+ (albumMedia.size()==1?" mục":" mục"));
        applyTimelineDisplay(); updateSelectionUi(); resolveCover();
    }

    private void applyTimelineDisplay(){
        if(binding==null || adapter==null) return;
        List<AlbumMediaEntry> visible=MediaTimelineUtils.filterAndSort(albumMedia,currentMediaFilter,currentViewMode,currentSortMode,
                AlbumMediaEntry::getTimestamp,AlbumMediaEntry::getName,AlbumMediaEntry::isVideo);
        adapter.setTimelineItems(visible,currentViewMode);
        boolean noResults=visible.isEmpty();
        binding.layoutEmpty.setVisibility(noResults?View.VISIBLE:View.GONE);
        binding.rvAlbumPhotos.setVisibility(noResults?View.GONE:View.VISIBLE);
        binding.tvAlbumEmptyMessage.setText(albumMedia.isEmpty()?R.string.album_empty:R.string.empty_media_filter);
    }

    private void selectTimeline(int viewMode,int mediaFilter){
        currentViewMode=viewMode; currentMediaFilter=mediaFilter;
        getSharedPreferences("photovault_library",MODE_PRIVATE).edit().putInt("view_mode",viewMode).putInt("media_filter",mediaFilter).apply();
        setCheckedChip(viewMode,mediaFilter); applyTimelineDisplay();
    }

    private void setCheckedChip(int viewMode,int mediaFilter){
        if(binding==null)return;
        if(viewMode==PhotoTimelineUtils.VIEW_MODE_DAYS)binding.chipAlbumDays.setChecked(true);
        else if(viewMode==PhotoTimelineUtils.VIEW_MODE_MONTHS)binding.chipAlbumMonths.setChecked(true);
        else if(viewMode==PhotoTimelineUtils.VIEW_MODE_YEARS)binding.chipAlbumYears.setChecked(true);
        else binding.chipAlbumAllTime.setChecked(true);
        if(mediaFilter==MediaTimelineUtils.FILTER_IMAGES)binding.chipAlbumImages.setChecked(true);
        else if(mediaFilter==MediaTimelineUtils.FILTER_VIDEOS)binding.chipAlbumVideos.setChecked(true);
        else binding.chipAlbumMediaAll.setChecked(true);
    }

    private void migrateLocalMembership(LocalMediaPhoto local,Photo cloud){
        repository.addPhotoToAlbumAsync(uid,cloud.getId(),album.getId(),new PhotoRepository.Callback<Void>(){
            @Override public void onSuccess(Void v){
                localStore.remove(uid,album.getId(),AlbumMediaPickerActivity.localKey(local));
                String cover=localStore.getCover(uid,album.getId());
                if(AlbumMediaPickerActivity.localKey(local).equals(cover)) repository.updateAlbumCoverUrlAsync(uid,album.getId(),cloud.getImageUrl(),new PhotoRepository.Callback<Void>(){
                    @Override public void onSuccess(Void ignored){ album.setCoverImageUrl(cloud.getImageUrl()); localStore.clearCover(uid,album.getId()); }
                    @Override public void onError(String error){ }
                });
            }
            @Override public void onError(String error){ }
        });
    }

    private void resolveCover(){
        if(albumMedia.isEmpty()){ Glide.with(this).load(R.drawable.ic_photo_placeholder).into(binding.imgAlbumHero); binding.tvAlbumMediaCount.setText(cloudLoadFailed?"Không thể tải album":"Album trống"); localStore.clearCover(uid,album.getId()); if(!cloudLoadFailed && album.getCoverImageUrl()!=null && !album.getCoverImageUrl().isEmpty()) clearCloudCover(); return; }
        String localCover=localStore.getCover(uid,album.getId()); AlbumMediaEntry chosen=null;
        if(localCover!=null) for(AlbumMediaEntry e:albumMedia) if(localCover.equals(e.getLocalKey()) && !unavailableCoverKeys.contains(coverKey(e))) { chosen=e; break; }
        if(chosen==null && album.getCoverImageUrl()!=null && !album.getCoverImageUrl().isEmpty()) for(AlbumMediaEntry e:albumMedia) if(e.getCloud()!=null && album.getCoverImageUrl().equals(e.getCloud().getImageUrl()) && !unavailableCoverKeys.contains(coverKey(e))) { chosen=e; break; }
        if(chosen==null){
            for(AlbumMediaEntry candidate:albumMedia) if(!unavailableCoverKeys.contains(coverKey(candidate)) && candidate.getSource()!=null) { chosen=candidate; break; }
            if(chosen==null){
                Glide.with(this).load(R.drawable.ic_photo_placeholder).into(binding.imgAlbumHero);
                localStore.clearCover(uid,album.getId());
                if(!cloudLoadFailed && !TextUtils.isEmpty(album.getCoverImageUrl())) clearCloudCover();
                return;
            }
            if(chosen.getCloud()!=null){ setCloudCover(chosen); localStore.clearCover(uid,album.getId()); }
            else { localStore.setCover(uid,album.getId(),chosen.getLocalKey()); if(!album.getCoverImageUrl().isEmpty()) clearCloudCover(); }
        }
        final AlbumMediaEntry coverEntry=chosen;
        Object src=coverEntry.getSource();
        if(coverEntry.isVideo()) Glide.with(this).asBitmap().load(src).apply(com.bumptech.glide.request.RequestOptions.frameOf(1_000_000)).listener(new RequestListener<Bitmap>() {
            @Override public boolean onLoadFailed(@Nullable GlideException e,Object model,Target<Bitmap> target,boolean first){ onCoverLoadFailed(coverEntry); return false; }
            @Override public boolean onResourceReady(Bitmap resource,Object model,Target<Bitmap> target,com.bumptech.glide.load.DataSource source,boolean first){ return false; }
        }).placeholder(R.drawable.ic_photo_placeholder).error(R.drawable.ic_photo_placeholder).centerCrop().into(binding.imgAlbumHero);
        else Glide.with(this).load(src).listener(new RequestListener<Drawable>() {
            @Override public boolean onLoadFailed(@Nullable GlideException e,Object model,Target<Drawable> target,boolean first){ onCoverLoadFailed(coverEntry); return false; }
            @Override public boolean onResourceReady(Drawable resource,Object model,Target<Drawable> target,com.bumptech.glide.load.DataSource source,boolean first){ return false; }
        }).placeholder(R.drawable.ic_photo_placeholder).error(R.drawable.ic_photo_placeholder).centerCrop().into(binding.imgAlbumHero);
    }

    private String coverKey(AlbumMediaEntry entry){ return entry.getCloud()!=null?"cloud:"+entry.getCloud().getId():entry.getLocalKey(); }
    private void onCoverLoadFailed(AlbumMediaEntry entry){
        if(entry!=null && entry.getCloud()!=null && !hasNetworkConnection()) {
            if(binding!=null) binding.imgAlbumHero.setImageResource(R.drawable.ic_photo_placeholder);
            return;
        }
        if(entry==null || !unavailableCoverKeys.add(coverKey(entry)) || isFinishing()) return;
        resolveCover();
    }

    @SuppressWarnings("deprecation")
    private boolean hasNetworkConnection(){
        ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo network=manager==null?null:manager.getActiveNetworkInfo();
        return network!=null && network.isConnected();
    }

    private void setCloudCover(AlbumMediaEntry entry){ if(entry!=null && entry.getCloud()!=null) setCloudCover(entry.getCloud()); }
    private void setCloudCover(Photo photo){
        if(photo==null || photo.getImageUrl()==null || photo.getImageUrl().isEmpty()) return;
        repository.updateAlbumCoverUrlAsync(uid,album.getId(),photo.getImageUrl(),new PhotoRepository.Callback<Void>(){
            @Override public void onSuccess(Void v){ album.setCoverImageUrl(photo.getImageUrl()); }
            @Override public void onError(String error){ Toast.makeText(AlbumDetailActivity.this,"Ảnh đã thêm nhưng chưa lưu được ảnh bìa: "+error,Toast.LENGTH_LONG).show(); }
        });
    }
    private void clearCloudCover(){ repository.updateAlbumCoverUrlAsync(uid,album.getId(),"",new PhotoRepository.Callback<Void>(){ @Override public void onSuccess(Void v){album.setCoverImageUrl("");} @Override public void onError(String e){} }); }

    private void openPicker(){ Intent i=new Intent(this,AlbumMediaPickerActivity.class); i.putExtra(AlbumMediaPickerActivity.EXTRA_ALBUM_ID,album.getId()); startActivityForResult(i,REQUEST_PICK_MEDIA); }
    @SuppressWarnings("unchecked") @Override protected void onActivityResult(int requestCode,int resultCode,@Nullable Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQUEST_PICK_MEDIA && resultCode==RESULT_OK && data!=null){ ArrayList<AlbumMediaEntry> chosen=(ArrayList<AlbumMediaEntry>)data.getSerializableExtra(AlbumMediaPickerActivity.EXTRA_SELECTED); if(chosen!=null && !chosen.isEmpty()) applyAddSequential(chosen,0,0,0,hasValidCover()); }
    }
    private boolean hasValidCover(){ String local=localStore.getCover(uid,album.getId()); if(local!=null) for(AlbumMediaEntry e:albumMedia) if(local.equals(e.getLocalKey())) return true; if(album.getCoverImageUrl()!=null) for(AlbumMediaEntry e:albumMedia) if(e.getCloud()!=null && album.getCoverImageUrl().equals(e.getCloud().getImageUrl())) return true; return false; }
    private void applyAddSequential(List<AlbumMediaEntry> chosen,int index,int success,int failures,boolean coverAssigned){
        UserInfo activeUser=authRepository.getCurrentUser();
        if(activeUser==null || !uid.equals(activeUser.getId())) { finish(); return; }
        if(index>=chosen.size()){ Toast.makeText(this,"Đã thêm "+success+"/"+chosen.size()+" mục"+(failures>0?"; lỗi "+failures:""),Toast.LENGTH_LONG).show(); loadAlbumPhotos(); return; }
        AlbumMediaEntry e=chosen.get(index);
        if(e.getCloud()!=null){
            repository.addPhotoToAlbumAsync(uid,e.getCloud().getId(),album.getId(),new PhotoRepository.Callback<Void>(){
                @Override public void onSuccess(Void v){ List<String> ids=new ArrayList<>(e.getCloud().getAlbumIds()); if(!ids.contains(album.getId())) ids.add(album.getId()); e.getCloud().setAlbumIds(ids); if(!coverAssigned && e.getCloud().getImageUrl()!=null && !e.getCloud().getImageUrl().isEmpty()) saveDefaultCloudCoverThenContinue(chosen,index,success+1,failures,e.getCloud()); else applyAddSequential(chosen,index+1,success+1,failures,coverAssigned); }
                @Override public void onError(String error){ applyAddSequential(chosen,index+1,success,failures+1,coverAssigned); }
            });
        } else {
            try {
                boolean added=localStore.add(uid,album.getId(),e.getLocalKey()) || localStore.hasMember(uid,album.getId(),e.getLocalKey());
                if(added && !coverAssigned){
                    localStore.setCover(uid,album.getId(),e.getLocalKey());
                    if(!TextUtils.isEmpty(album.getCoverImageUrl())) clearCloudCover();
                    applyAddSequential(chosen,index+1,success+1,failures,true);
                } else applyAddSequential(chosen,index+1,success+(added?1:0),failures+(added?0:1),coverAssigned);
            } catch (RuntimeException error) {
                applyAddSequential(chosen,index+1,success,failures+1,coverAssigned);
            }
        }
    }
    private void saveDefaultCloudCoverThenContinue(List<AlbumMediaEntry> chosen,int index,int success,int failures,Photo photo){
        repository.updateAlbumCoverUrlAsync(uid,album.getId(),photo.getImageUrl(),new PhotoRepository.Callback<Void>(){
            @Override public void onSuccess(Void v){ album.setCoverImageUrl(photo.getImageUrl()); localStore.clearCover(uid,album.getId()); applyAddSequential(chosen,index+1,success,failures,true); }
            @Override public void onError(String e){ applyAddSequential(chosen,index+1,success,failures,false); }
        });
    }

    private void toggleSelectionMode(){ selectionMode=!selectionMode; if(!selectionMode) selected.clear(); adapter.setSelectionMode(selectionMode); updateSelectionUi(); }
    private void exitSelectionMode(){ selectionMode=false; selected.clear(); for(AlbumMediaEntry e:albumMedia)e.setSelected(false); adapter.setSelectionMode(false); updateSelectionUi(); }
    private void updateSelectionUi(){
        binding.layoutAlbumSelection.setVisibility(selectionMode?View.VISIBLE:View.GONE); binding.btnAddAlbumMedia.setVisibility(selectionMode?View.GONE:View.VISIBLE); binding.btnEditAlbum.setVisibility(selectionMode?View.GONE:View.VISIBLE); binding.btnDeleteAlbum.setVisibility(selectionMode?View.GONE:View.VISIBLE); binding.tvAlbumTitle.setText(selectionMode?"Đã chọn "+selected.size():album.getName()); binding.tvAlbumSelectionCount.setText("Đã chọn "+selected.size()+" mục"); binding.btnRemoveAlbumSelection.setEnabled(!selected.isEmpty());
    }
    @Override public void onClick(AlbumMediaEntry e){
        if(applying) return;
        if(selectionMode){ toggleEntrySelection(e); return; }
        openMedia(e);
    }
    @Override public void onLongClick(AlbumMediaEntry e,View anchor){
        if(applying)return;
        if(selectionMode){ toggleEntrySelection(e); return; }
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add(0,ACTION_SET_COVER,0,"Đặt làm ảnh bìa");
        menu.getMenu().add(0,ACTION_REMOVE,1,"Gỡ khỏi album");
        menu.setOnMenuItemClickListener(item->{
            if(item.getItemId()==ACTION_SET_COVER){ setCoverManually(e); return true; }
            if(item.getItemId()==ACTION_REMOVE){ new AlertDialog.Builder(this).setTitle("Gỡ khỏi album?").setMessage("Media vẫn còn trong thư viện và các album khác.").setNegativeButton("Hủy",null).setPositiveButton("Gỡ khỏi album",(d,w)->removeOne(e,()->loadAlbumPhotos(),()->Toast.makeText(this,"Không gỡ được media khỏi album",Toast.LENGTH_LONG).show())).show(); return true; }
            return false;
        });
        menu.show();
    }
    private void setCoverManually(AlbumMediaEntry e){
        if(!albumMedia.contains(e)) return;
        if(e.getCloud()!=null){
            if(e.getCloud().getImageUrl()==null || e.getCloud().getImageUrl().isEmpty()){ Toast.makeText(this,"Media này chưa có ảnh cloud dùng làm bìa",Toast.LENGTH_LONG).show(); return; }
            repository.updateAlbumCoverUrlAsync(uid,album.getId(),e.getCloud().getImageUrl(),new PhotoRepository.Callback<Void>(){
                @Override public void onSuccess(Void v){ album.setCoverImageUrl(e.getCloud().getImageUrl()); localStore.clearCover(uid,album.getId()); resolveCover(); Toast.makeText(AlbumDetailActivity.this,"Đã đặt ảnh bìa album",Toast.LENGTH_SHORT).show(); }
                @Override public void onError(String error){ Toast.makeText(AlbumDetailActivity.this,"Không lưu được ảnh bìa: "+error,Toast.LENGTH_LONG).show(); }
            });
        } else {
            localStore.setCover(uid,album.getId(),e.getLocalKey()); resolveCover(); Toast.makeText(this,"Đã đặt ảnh bìa trên thiết bị này",Toast.LENGTH_SHORT).show();
        }
    }
    private void toggleEntrySelection(AlbumMediaEntry e){ e.setSelected(!e.isSelected()); if(e.isSelected()) selected.add(e.getKey()); else selected.remove(e.getKey()); adapter.notifyEntryChanged(e); updateSelectionUi(); }
    private void openMedia(AlbumMediaEntry e){
        Photo current=PhotoDetailActivity.photoForLocalMedia(e.getLocal());
        List<AlbumMediaEntry> visibleEntries=adapter==null?albumMedia:adapter.getItems();
        List<Photo> collection=new ArrayList<>(); for(AlbumMediaEntry item:visibleEntries) collection.add(PhotoDetailActivity.photoForLocalMedia(item.getLocal()));
        startActivity(PhotoDetailActivity.createIntent(this,current,collection));
    }
    private void confirmRemoveSelected(){ if(selected.isEmpty())return; new AlertDialog.Builder(this).setTitle("Gỡ khỏi album?").setMessage("Gỡ "+selected.size()+" mục khỏi album. Media vẫn còn trong thư viện và các album khác.").setNegativeButton("Hủy",null).setPositiveButton("Gỡ khỏi album",(d,w)->removeSequential(selectedEntries(),0,0,0)).show(); }
    private List<AlbumMediaEntry> selectedEntries(){ List<AlbumMediaEntry> out=new ArrayList<>(); for(AlbumMediaEntry e:albumMedia)if(selected.contains(e.getKey()))out.add(e); return out; }
    private void removeOne(AlbumMediaEntry e,Runnable success,Runnable failure){
        UserInfo activeUser=authRepository.getCurrentUser();
        if(activeUser==null || !uid.equals(activeUser.getId())) { finish(); return; }
        try {
            if(e.getCloud()!=null){
                String localKey=e.getLocalKey();
                boolean hadLocalMembership=e.getLocal()!=null && localStore.hasMember(uid,album.getId(),localKey);
                if(hadLocalMembership && !localStore.remove(uid,album.getId(),localKey)){ failure.run(); return; }
                repository.removePhotoFromAlbumAsync(uid,e.getCloud().getId(),album.getId(),new PhotoRepository.Callback<Void>(){
                    @Override public void onSuccess(Void v){ success.run(); }
                    @Override public void onError(String error){
                        if(hadLocalMembership) try { localStore.add(uid,album.getId(),localKey); } catch(RuntimeException ignored) { }
                        failure.run();
                    }
                });
            } else {
                boolean ok=localStore.remove(uid,album.getId(),e.getLocalKey());
                if(ok || !localStore.hasMember(uid,album.getId(),e.getLocalKey())) success.run(); else failure.run();
            }
        } catch(RuntimeException error) { failure.run(); }
    }
    private void removeSequential(List<AlbumMediaEntry> work,int index,int success,int failures){
        if(index>=work.size()){ applying=false; exitSelectionMode(); Toast.makeText(this,"Đã gỡ "+success+" mục; lỗi "+failures,Toast.LENGTH_LONG).show(); loadAlbumPhotos(); return; }
        applying=true; removeOne(work.get(index),()->removeSequential(work,index+1,success+1,failures),()->removeSequential(work,index+1,success,failures+1));
    }
    private void showEditAlbumNameDialog(){ EditText input=new EditText(this); input.setText(album.getName()); input.setHint(R.string.album_name_hint); new AlertDialog.Builder(this).setTitle(R.string.rename_album).setView(input).setNegativeButton(R.string.btn_cancel,null).setPositiveButton(R.string.btn_save,(d,w)->{String name=input.getText().toString().trim(); if(TextUtils.isEmpty(name))return; repository.updateAlbumNameAsync(uid,album.getId(),name,new PhotoRepository.Callback<Void>(){ @Override public void onSuccess(Void v){album.setName(name); binding.tvAlbumTitle.setText(name); Toast.makeText(AlbumDetailActivity.this,"Đã đổi tên album",Toast.LENGTH_SHORT).show();} @Override public void onError(String e){Toast.makeText(AlbumDetailActivity.this,"Không lưu được tên album: "+e,Toast.LENGTH_LONG).show();}});}).show(); }
    private void confirmDeleteAlbum(){ new AlertDialog.Builder(this).setTitle("Xóa album").setMessage(R.string.confirm_delete_album).setNegativeButton("Hủy",null).setPositiveButton("Xóa",(d,w)->repository.deleteAlbumAsync(uid,album.getId(),new PhotoRepository.Callback<Void>(){ @Override public void onSuccess(Void v){localStore.deleteAlbum(uid,album.getId()); Toast.makeText(AlbumDetailActivity.this,"Đã xóa album",Toast.LENGTH_SHORT).show(); finish();} @Override public void onError(String e){Toast.makeText(AlbumDetailActivity.this,"Lỗi xóa album: "+e,Toast.LENGTH_LONG).show();}})).show(); }
    @Override protected void onResume(){
        super.onResume();
        if(binding!=null){
            currentSortMode=getSharedPreferences("photovault_library",MODE_PRIVATE).getInt("sort_mode",R.id.sort_date_desc);
            currentViewMode=getSharedPreferences("photovault_library",MODE_PRIVATE).getInt("view_mode",PhotoTimelineUtils.VIEW_MODE_ALL);
            currentMediaFilter=getSharedPreferences("photovault_library",MODE_PRIVATE).getInt("media_filter",MediaTimelineUtils.FILTER_ALL);
            setCheckedChip(currentViewMode,currentMediaFilter); applyTimelineDisplay();
        }
        if(firstResume){ firstResume=false; return; }
        if(binding!=null && adapter!=null) loadAlbumPhotos();
    }
    @Override public void onBackPressed(){ if(selectionMode){ exitSelectionMode(); return; } super.onBackPressed(); }
    @Override protected void onDestroy(){ if(localStore!=null)localStore.close(); super.onDestroy(); }
}
