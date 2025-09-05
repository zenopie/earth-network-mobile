package com.example.earthwallet.ui.pages.managelp;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * ViewPager2 adapter for liquidity management tabs
 */
public class LiquidityTabsAdapter extends FragmentStateAdapter {

    private LiquidityManagementComponent parentComponent;

    public LiquidityTabsAdapter(LiquidityManagementComponent parentComponent) {
        super(parentComponent);
        this.parentComponent = parentComponent;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return new LiquidityTabFragment(position, parentComponent);
    }

    @Override
    public int getItemCount() {
        return 4; // Info, Add, Remove, Unbond
    }

    /**
     * Fragment wrapper for each tab content
     */
    public static class LiquidityTabFragment extends Fragment {
        private int tabPosition;
        private LiquidityManagementComponent parentComponent;

        public LiquidityTabFragment(int tabPosition, LiquidityManagementComponent parentComponent) {
            this.tabPosition = tabPosition;
            this.parentComponent = parentComponent;
        }

        @Override
        public View onCreateView(@NonNull android.view.LayoutInflater inflater, 
                                ViewGroup container, 
                                android.os.Bundle savedInstanceState) {
            switch (tabPosition) {
                case 0: return parentComponent.createInfoTab();
                case 1: return parentComponent.createAddTab();
                case 2: return parentComponent.createRemoveTab();
                case 3: return parentComponent.createUnbondTab();
                default: return new View(getContext());
            }
        }
    }
}