package com.example.earthwallet.ui.pages.anml;

import com.example.earthwallet.R;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

public class ScannerFragment extends Fragment implements PassportScannerFragment.PassportScannerListener {
    
    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFilters;
    private String[][] techLists;
    private PassportScannerFragment passportScannerFragment;
    
    public ScannerFragment() {}

    public static ScannerFragment newInstance() { return new ScannerFragment(); }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Return a simple container for the PassportScannerFragment
        return inflater.inflate(R.layout.fragment_scanner_container, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize and show PassportScannerFragment
        passportScannerFragment = PassportScannerFragment.newInstance();
        passportScannerFragment.setPassportScannerListener(this);
        
        FragmentManager fm = getChildFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.scanner_fragment_container, passportScannerFragment, "passport_scanner");
        ft.commit();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (nfcAdapter != null && getActivity() != null) {
            nfcAdapter.enableForegroundDispatch(getActivity(), pendingIntent, intentFilters, techLists);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (nfcAdapter != null && getActivity() != null) {
            nfcAdapter.disableForegroundDispatch(getActivity());
        }
    }

    // Handle NFC intents from parent activity
    public void handleNfcIntent(Intent intent) {
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction()) && passportScannerFragment != null) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                passportScannerFragment.handleNfcTag(tag);
            }
        }
    }

    @Override
    public void onNFCTagDetected(Tag tag) {
        // This shouldn't be called since we handle NFC at the fragment level
        if (passportScannerFragment != null) {
            passportScannerFragment.handleNfcTag(tag);
        }
    }

    @Override
    public void requestNFCSetup() {
        // Set up NFC
        if (getActivity() != null) {
            nfcAdapter = NfcAdapter.getDefaultAdapter(getActivity());
            if (nfcAdapter == null) {
                // Device doesn't support NFC
                return;
            }

            Intent nfcIntent = new Intent(getActivity(), getActivity().getClass());
            nfcIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            pendingIntent = PendingIntent.getActivity(getActivity(), 0, nfcIntent, PendingIntent.FLAG_MUTABLE);

            intentFilters = null;
            techLists = new String[][] { new String[] { IsoDep.class.getName() } };
        }
    }
    
}