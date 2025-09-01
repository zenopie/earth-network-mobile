package com.example.earthwallet.ui.fragments;

import com.example.earthwallet.R;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import java.util.Calendar;

public class ANMLCompleteFragment extends Fragment {
    
    public ANMLCompleteFragment() {}
    
    public static ANMLCompleteFragment newInstance() {
        return new ANMLCompleteFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_anml_complete, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        TextView foodEmojiView = view.findViewById(R.id.food_emoji);
        if (foodEmojiView != null) {
            String foodEmoji = getDailyFoodEmoji();
            foodEmojiView.setText(foodEmoji);
            
            // Start floating animation
            startFloatingAnimation(foodEmojiView);
        }
    }

    private void startFloatingAnimation(View view) {
        // Create floating animation that moves up and down
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, "translationY", 0f, -20f, 0f);
        animator.setDuration(2000); // 2 seconds per cycle
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.start();
    }

    private String getDailyFoodEmoji() {
        Calendar calendar = Calendar.getInstance();
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1; // Convert to 0-6 range
        
        String[][] weekdayFoods = {
            {"🥞", "🧇", "🥐", "🍳", "🥯", "🍉", "☕"}, // Sunday
            {"🥗", "🥑", "🍉", "🍓", "🥝", "🥥", "🥦"}, // Monday
            {"🌮", "🌯", "🥙", "🌶️", "🍹", "🧀"}, // Tuesday
            {"🍲", "🍜", "🍝", "🍛", "🥪", "🍚"}, // Wednesday
            {"🍣", "🥟", "🫕", "🍱", "🥡", "🥘", "🍛"}, // Thursday
            {"🍕", "🍔", "🍟", "🍉", "🍻", "🍿", "🌭"}, // Friday
            {"🍦", "🧁", "🎂", "🍰", "🍪", "🍫", "🍮"}, // Saturday
        };
        
        String[] todaysFoods = weekdayFoods[dayOfWeek];
        int randomIndex = (int) (Math.random() * todaysFoods.length);
        return todaysFoods[randomIndex];
    }
}