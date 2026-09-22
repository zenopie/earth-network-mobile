# App Store Connect copy

Drafts for the App Store listing and App Review. Kept in the repo so the
next submission starts from what was actually filed rather than from memory.

---

## Subtitle (30 char max)

    Proof-of-personhood wallet

---

## Promotional text (170 char max)

    A self-custody wallet for earth-1. Prove you are a unique human by reading
    your passport's chip — the proof is made on your device and nothing about
    the passport leaves it.

---

## Description

    Earth Wallet is a self-custody wallet for earth-1, a network where one
    person means one vote and one daily share.

    PROVE YOU ARE HUMAN, WITHOUT GIVING UP YOUR PASSPORT

    Registration reads the NFC chip in your ePassport and builds a
    zero-knowledge proof on your phone. The proof shows the network that a
    genuine, unexpired passport was read and that this is the first time it
    has been used — and nothing else. Your name, date of birth, photo and
    document number never leave the device. No server sees them, because none
    is involved.

    YOUR KEYS, YOUR WALLET

    Keys are generated on your device and stored in the Secure Enclave-backed
    keychain, behind a PIN and Face ID. Your recovery phrase is shown once, to
    you. There is no account, no custodian, and no password to reset — losing
    the phrase means losing the wallet, so write it down.

    WHAT YOU CAN DO

    • Claim a daily ANML token, once registered
    • Send and receive ERTH and ANML
    • Swap tokens, and provide liquidity to earn a share of trading fees
    • Stake ERTH and claim staking rewards
    • Vote on proposals, and direct where the network's emissions go
    • Track balances, pools and transaction history

    REQUIREMENTS

    An ePassport — a passport with the chip symbol on the cover — is needed to
    register. Most passports issued in the last fifteen years have one. The
    wallet itself works without registering; only personhood and the daily
    claim need the passport read.

---

## Keywords (100 char max, comma separated)

    wallet,crypto,cosmos,passport,nfc,zero knowledge,personhood,earth,erth,anml,self custody

---

## Support URL / Marketing URL

    TODO — a reachable page is required. https://erth.network if it serves one.

---

## App Review Information

### Sign-in required

**Yes** — the app needs a wallet before most screens do anything.

### Demo account

There is no username or password. The wallet is restored from a recovery
phrase, and the reviewer chooses the PIN during setup.

    Phrase:  abandon abandon abandon abandon abandon abandon abandon abandon
             abandon abandon abandon about

To use it: open the app → Restore an existing wallet → give it any name →
choose a PIN (the reviewer's own, any 6 digits) → enter the phrase above.

This is the published BIP-39 all-zero test vector, used deliberately. It is
public knowledge, so nothing secret is being handed over, and it must never
hold value — anyone in the world can spend from it.

### Notes

    WHAT CANNOT BE TESTED WITHOUT A PASSPORT

    Registration ("Verify") reads the NFC chip of a physical ePassport. There
    is no way to simulate this: the chip answers a challenge that only the
    real document can answer, which is the whole point of the feature. Without
    a passport in hand, tapping Verify reaches the scan screen and waits for a
    document that never arrives.

    Everything else works on the demo wallet above: balances, send and receive,
    swap, liquidity, staking, governance, and transaction history all read live
    from the earth-1 network.

    The demo wallet is not registered, so the daily ANML claim will show as
    unavailable. That is the correct state for an unregistered account rather
    than a defect.

    WHY NFC AND CAMERA ARE REQUESTED

    The camera reads the two printed lines at the bottom of the passport's
    photo page. Those characters are the key that unlocks the chip — this is
    how the ICAO standard works, and the chip refuses to answer without them.
    Neither the scan nor the chip contents are stored or transmitted.

    ADS

    The app offers an optional rewarded video to cover transaction fees for
    users with an empty balance. On a build that is not yet public, the ad
    unit usually returns "No fill" — an empty ad slot rather than a broken one.

    CRYPTOCURRENCY

    Earth Wallet is non-custodial: it holds no user funds, and the developer
    cannot access, freeze or recover them. It is submitted by ERTH
    TECHNOLOGIES LLC, an organization account, per guideline 3.1.5(b).
