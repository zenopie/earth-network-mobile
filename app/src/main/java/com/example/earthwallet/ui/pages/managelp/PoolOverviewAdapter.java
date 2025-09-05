package com.example.earthwallet.ui.pages.managelp;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.earthwallet.R;

import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
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
            
            // Set pool statistics with proper formatting
            rewardsValue.setText(formatNumber(pool.getPendingRewards()));
            rewardsLabel.setText("Rewards");
            
            liquidityValue.setText(formatNumber(pool.getLiquidity()));
            liquidityLabel.setText("Liquidity (ERTH)");
            
            volumeValue.setText(formatNumber(pool.getVolume()));
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
            if (tokenLogo == null || itemView.getContext() == null) return;
            
            try {
                // Get token info and load logo from assets
                if (currentPool != null && currentPool.getTokenInfo() != null) {
                    String logoPath = currentPool.getTokenInfo().logo;
                    if (logoPath != null && !logoPath.isEmpty()) {
                        loadImageFromAssets(itemView.getContext(), logoPath);
                        return;
                    }
                }
                
                // Fallback to token key based loading
                String assetPath = "coin/" + tokenKey.toUpperCase() + ".png";
                loadImageFromAssets(itemView.getContext(), assetPath);
                
            } catch (Exception e) {
                Log.w("PoolOverviewAdapter", "Failed to load token logo for " + tokenKey + ": " + e.getMessage());
                // Fallback to default
                tokenLogo.setImageResource(R.drawable.ic_token_default);
            }
        }
        
        private void loadImageFromAssets(Context context, String assetPath) {
            try {
                InputStream inputStream = context.getAssets().open(assetPath);
                Drawable drawable = Drawable.createFromStream(inputStream, null);
                tokenLogo.setImageDrawable(drawable);
                inputStream.close();
                Log.d("PoolOverviewAdapter", "Successfully loaded logo from: " + assetPath);
            } catch (IOException e) {
                Log.w("PoolOverviewAdapter", "Failed to load asset: " + assetPath + ", using default");
                tokenLogo.setImageResource(R.drawable.ic_token_default);
            }
        }
        
        private String formatNumber(String numberStr) {
            if (numberStr == null || numberStr.trim().isEmpty()) {
                return "0";
            }
            
            try {
                // Remove any commas and parse
                double number = Double.parseDouble(numberStr.replace(",", ""));
                
                if (number == 0) {
                    return "0";
                }
                
                DecimalFormat formatter;
                if (number >= 1000000) {
                    // Show millions with 1 decimal place
                    formatter = new DecimalFormat("#.#M");
                    return formatter.format(number / 1000000);
                } else if (number >= 1000) {
                    // Show thousands with 1 decimal place  
                    formatter = new DecimalFormat("#.#K");
                    return formatter.format(number / 1000);
                } else if (number >= 1) {
                    // Show whole numbers or 1 decimal place
                    formatter = new DecimalFormat("#.#");
                    return formatter.format(number);
                } else {
                    // Show small numbers with more precision
                    formatter = new DecimalFormat("#.###");
                    return formatter.format(number);
                }
            } catch (NumberFormatException e) {
                Log.w("PoolOverviewAdapter", "Failed to format number: " + numberStr);
                return numberStr; // Return as-is if parsing fails
            }
        }
    }
    
    public void updateData(List<ManageLPFragment.PoolData> newPoolList) {
        this.poolList = newPoolList;
        notifyDataSetChanged();
    }
}