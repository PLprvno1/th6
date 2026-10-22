package com.example.th6.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.th6.R;
import com.example.th6.databinding.ActivitySlideshowBinding;
import com.example.th6.models.Photo;

import java.util.ArrayList;
import java.util.List;

public class SlideshowActivity extends AppCompatActivity {

    private ActivitySlideshowBinding binding;
    private List<Photo> photos = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = true;
    private Handler handler;
    private Runnable runnable;
    private static final long SLIDE_DELAY = 3000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySlideshowBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (getIntent().hasExtra("photos")) {
            photos = (List<Photo>) getIntent().getSerializableExtra("photos");
        }

        if (photos == null || photos.isEmpty()) {
            Toast.makeText(this, "Không có ảnh nào để trình chiếu", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        handler = new Handler(Looper.getMainLooper());
        runnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && photos.size() > 1) {
                    currentIndex = (currentIndex + 1) % photos.size();
                    displayPhoto(currentIndex);
                    handler.postDelayed(this, SLIDE_DELAY);
                }
            }
        };

        setupUI();
        setupListeners();
        displayPhoto(0);
        startSlideshow();
    }

    private void setupUI() {
        binding.btnPlayPause.setImageResource(R.drawable.ic_dark_mode); // Icon toggle
    }

    private void setupListeners() {
        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnPlayPause.setOnClickListener(v -> {
            isPlaying = !isPlaying;
            if (isPlaying) {
                startSlideshow();
                Toast.makeText(this, "Tiếp tục trình chiếu", Toast.LENGTH_SHORT).show();
            } else {
                stopSlideshow();
                Toast.makeText(this, "Đã tạm dừng", Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnNext.setOnClickListener(v -> {
            if (!photos.isEmpty()) {
                currentIndex = (currentIndex + 1) % photos.size();
                displayPhoto(currentIndex);
            }
        });

        binding.btnPrev.setOnClickListener(v -> {
            if (!photos.isEmpty()) {
                currentIndex = (currentIndex - 1 + photos.size()) % photos.size();
                displayPhoto(currentIndex);
            }
        });
    }

    private void displayPhoto(int index) {
        if (index < 0 || index >= photos.size()) return;

        Photo p = photos.get(index);
        binding.tvCounter.setText((index + 1) + " / " + photos.size());

        Glide.with(this)
                .load(p.getImageUrl())
                .placeholder(R.drawable.ic_photo_placeholder)
                .into(binding.imgSlideshow);
    }

    private void startSlideshow() {
        handler.removeCallbacks(runnable);
        handler.postDelayed(runnable, SLIDE_DELAY);
    }

    private void stopSlideshow() {
        handler.removeCallbacks(runnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSlideshow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isPlaying) {
            startSlideshow();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSlideshow();
    }
}
