package com.example.th6.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.th6.databinding.DialogPhotoInfoBinding;
import com.example.th6.models.Photo;
import com.example.th6.utils.DateFormatter;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class PhotoInfoBottomSheetDialog extends BottomSheetDialogFragment {

    private DialogPhotoInfoBinding binding;
    private Photo photo;

    public static PhotoInfoBottomSheetDialog newInstance(Photo photo) {
        PhotoInfoBottomSheetDialog fragment = new PhotoInfoBottomSheetDialog();
        Bundle args = new Bundle();
        args.putSerializable("photo", photo);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            photo = (Photo) getArguments().getSerializable("photo");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogPhotoInfoBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (photo != null) {
            binding.tvInfoName.setText(photo.getName());
            binding.tvInfoDate.setText(DateFormatter.formatTimestamp(photo.getCreatedAt()));
            binding.tvInfoUrl.setText(photo.getImageUrl());
            boolean local = photo.getLocalMediaUri() != null && !photo.getLocalMediaUri().isEmpty();
            String provider = photo.getStorageProvider();
            boolean cloud = photo.getStoragePath() != null && !photo.getStoragePath().isEmpty()
                    && ("supabase".equalsIgnoreCase(provider) || "imgbb".equalsIgnoreCase(provider));
            String cloudName = "supabase".equalsIgnoreCase(provider) ? "Supabase Cloud"
                    : "imgbb".equalsIgnoreCase(provider) ? "ImgBB Cloud" : null;
            String source = local && cloud && cloudName != null ? "Thiết bị + " + cloudName
                    : local ? "Thiết bị" : cloudName != null && cloud ? cloudName : "Không xác định";
            binding.tvInfoProvider.setText(source);
        }

        binding.btnCloseInfo.setOnClickListener(v -> dismiss());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
