package com.example.earthwallet.ui.pages.managelp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.earthwallet.R;

import java.util.List;

/**
 * RecyclerView adapter for displaying pool overview items
 */
public class PoolOverviewAdapter extends RecyclerView.Adapter<PoolOverviewAdapter.PoolViewHolder> {

    private List<ManageLPFragment.PoolData> poolList;
    private PoolClickListener clickListener;

    public interface PoolClickListener {
        void onManageClicked(ManageLPFragment.PoolData poolData);
        void onClaimClicked(ManageLPFragment.PoolData poolData);
    }

    public PoolOverviewAdapter(List<ManageLPFragment.PoolData> poolList, PoolClickListener clickListener) {
        this.poolList = poolList;
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public PoolViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pool_overview, parent, false);
        return new PoolViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PoolViewHolder holder, int position) {
        ManageLPFragment.PoolData pool = poolList.get(position);
        holder.bind(pool, clickListener);
    }

    @Override
    public int getItemCount() {
        return poolList.size();
    }

    static class PoolViewHolder extends RecyclerView.ViewHolder {
        private ImageView tokenLogo;
        private TextView tokenLabel;
        private TextView pairLabel;
        private TextView rewardsValue;
        private TextView rewardsLabel;
        private TextView liquidityValue;
        private TextView liquidityLabel;
        private TextView volumeValue;
        private TextView volumeLabel;
        private TextView aprValue;
        private TextView aprLabel;
        private Button claimButton;
        private Button manageButton;
        private View poolBox;
        private View unbondingLock;
        private ManageLPFragment.PoolData currentPool;

        public PoolViewHolder(@NonNull View itemView) {
            super(itemView);
            
            // Initialize views
            tokenLogo = itemView.findViewById(R.id.token_logo);
            tokenLabel = itemView.findViewById(R.id.token_label);
            pairLabel = itemView.findViewById(R.id.pair_label);
            rewardsValue = itemView.findViewById(R.id.rewards_value);
            rewardsLabel = itemView.findViewById(R.id.rewards_label);
            liquidityValue = itemView.findViewById(R.id.liquidity_value);
            liquidityLabel = itemView.findViewById(R.id.liquidity_label);
            volumeValue = itemView.findViewById(R.id.volume_value);
            volumeLabel = itemView.findViewById(R.id.volume_label);
            aprValue = itemView.findViewById(R.id.apr_value);
            aprLabel = itemView.findViewById(R.id.apr_label);
            claimButton = itemView.findViewById(R.id.claim_button);
            manageButton = itemView.findViewById(R.id.manage_button);
            poolBox = itemView.findViewById(R.id.pool_overview_box);
            unbondingLock = itemView.findViewById(R.id.unbonding_lock);
        }

        public void bind(ManageLPFragment.PoolData pool, PoolClickListener clickListener) {
            // Store pool reference for logo loading
            this.currentPool = pool;
            // Set token information
            tokenLabel.setText(pool.getTokenKey());
            pairLabel.setText("/" + "ERTH");
            
            // Set pool statistics
            rewardsValue.setText(pool.getPendingRewards());
            rewardsLabel.setText("Rewards");
            
            liquidityValue.setText(pool.getLiquidity());
            liquidityLabel.setText("Liquidity (ERTH)");
            
            volumeValue.setText(pool.getVolume());
            volumeLabel.setText("Volume (7d)");
            
            aprValue.setText(pool.getApr());
            aprLabel.setText("APR");
            
            // Check if there are rewards to determine styling
            boolean tempHasRewards = false;
            try {
                double rewards = Double.parseDouble(pool.getPendingRewards().replace(",", ""));
                tempHasRewards = rewards > 0;
            } catch (NumberFormatException e) {
                tempHasRewards = false;
            }
            final boolean hasRewards = tempHasRewards;
            
            // Apply green outline if has rewards
            if (hasRewards && poolBox != null) {
                poolBox.setBackgroundResource(R.drawable.pool_overview_box_rewards);
            } else if (poolBox != null) {
                poolBox.setBackgroundResource(R.drawable.pool_overview_box_normal);
            }
            
            // Set button states and click listeners
            claimButton.setEnabled(hasRewards);
            claimButton.setOnClickListener(v -> {
                if (clickListener != null && hasRewards) {
                    clickListener.onClaimClicked(pool);
                }
            });
            
            manageButton.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onManageClicked(pool);
                }
            });
            
            // Set token logo based on token type
            setTokenLogo(pool.getTokenKey());
            
            // TODO: Implement unbonding lock visualization
            // For now, hide the unbonding lock until we have actual unbonding data
            if (unbondingLock != null) {
                unbondingLock.setVisibility(View.GONE);
            }
        }
        
        private void setTokenLogo(String tokenKey) {
            if (tokenLogo == null) return;
            
            try {
                // Get token info and load logo from assets
                if (currentPool != null && currentPool.getTokenInfo() != null) {
                    String logoPath = currentPool.getTokenInfo().logo;
                    // TODO: Load image from assets using logoPath (e.g., "coin/ANML.png")
                    // For now, use default based on token key
                }
            } catch (Exception e) {
                // Fallback to default
            }
            
            // Temporary fallback until asset loading is implemented
            switch (tokenKey.toLowerCase()) {
                case "sscrt":
                case "anml":
                    tokenLogo.setImageResource(R.drawable.ic_token_default);
                    break;
                default:
                    tokenLogo.setImageResource(R.drawable.ic_token_default);
                    break;
            }
        }
    }
    
    public void updateData(List<ManageLPFragment.PoolData> newPoolList) {
        this.poolList = newPoolList;
        notifyDataSetChanged();
    }
}