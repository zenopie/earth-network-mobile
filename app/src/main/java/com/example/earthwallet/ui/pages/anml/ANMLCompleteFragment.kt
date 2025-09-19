package com.example.earthwallet.ui.pages.anml

import com.example.earthwallet.R
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.Calendar
import kotlin.random.Random

class ANMLCompleteFragment : Fragment() {

    companion object {
        @JvmStatic
        fun newInstance(): ANMLCompleteFragment = ANMLCompleteFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_anml_complete, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val foodEmojiView = view.findViewById<TextView>(R.id.food_emoji)
        foodEmojiView?.let {
            val foodEmoji = getDailyFoodEmoji()
            it.text = foodEmoji

            // Start floating animation
            startFloatingAnimation(it)
        }
    }

    private fun startFloatingAnimation(view: View) {
        // Create floating animation that moves up and down
        val animator = ObjectAnimator.ofFloat(view, "translationY", 0f, -20f, 0f)
        animator.duration = 2000 // 2 seconds per cycle
        animator.repeatCount = ValueAnimator.INFINITE
        animator.repeatMode = ValueAnimator.RESTART
        animator.start()
    }

    private fun getDailyFoodEmoji(): String {
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1 // Convert to 0-6 range

        val weekdayFoods = arrayOf(
            arrayOf("🥞", "🧇", "🥐", "🍳", "🥯", "🍉", "☕"), // Sunday
            arrayOf("🥗", "🥑", "🍉", "🍓", "🥝", "🥥", "🥦"), // Monday
            arrayOf("🌮", "🌯", "🥙", "🌶️", "🍹", "🧀"), // Tuesday
            arrayOf("🍲", "🍜", "🍝", "🍛", "🥪", "🍚"), // Wednesday
            arrayOf("🍣", "🥟", "🫕", "🍱", "🥡", "🥘", "🍛"), // Thursday
            arrayOf("🍕", "🍔", "🍟", "🍉", "🍻", "🍿", "🌭"), // Friday
            arrayOf("🍦", "🧁", "🎂", "🍰", "🍪", "🍫", "🍮"), // Saturday
        )

        val todaysFoods = weekdayFoods[dayOfWeek]
        val randomIndex = Random.nextInt(todaysFoods.size)
        return todaysFoods[randomIndex]
    }
}