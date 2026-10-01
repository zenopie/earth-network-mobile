// Command privacyvectors prints golden vectors from the chain's own code for
// the Android privacy core (android/app/src/test/resources/privacy/
// vectors.json): Poseidon2, the zk/privacy derivations, scopes and signals,
// the zk/merkle tree, every private msg's proto encoding and signal, and an
// unsigned private tx. Run it through gen.sh, which points a throwaway module
// at a committed chain ref; nothing here has a second definition to drift
// from.
package main

import (
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"

	"cosmossdk.io/math"
	"github.com/consensys/gnark-crypto/ecc/bn254/fr"
	addresscodec "github.com/cosmos/cosmos-sdk/codec/address"
	codectypes "github.com/cosmos/cosmos-sdk/codec/types"
	sdk "github.com/cosmos/cosmos-sdk/types"
	txtypes "github.com/cosmos/cosmos-sdk/types/tx"
	govv1 "github.com/cosmos/cosmos-sdk/x/gov/types/v1"
	"github.com/cosmos/gogoproto/proto"

	allocationtypes "github.com/earth-network/earth/x/allocation/types"
	assemblytypes "github.com/earth-network/earth/x/assembly/types"
	dextypes "github.com/earth-network/earth/x/dex/types"
	personhoodtypes "github.com/earth-network/earth/x/personhood/types"
	shieldedtypes "github.com/earth-network/earth/x/shielded/types"
	stakingtypes "github.com/earth-network/earth/x/shieldedstaking/types"
	"github.com/earth-network/earth/zk/merkle"
	"github.com/earth-network/earth/zk/poseidon2"
	"github.com/earth-network/earth/zk/privacy"
)

const chainID = "earth-1"

func hx(e fr.Element) string { return hex.EncodeToString(privacy.FieldBytes(e)) }
func u(v uint64) fr.Element  { return privacy.U64(v) }

// fe is a deterministic, realistic field element.
func fe(i uint64) fr.Element { return poseidon2.Hash([]fr.Element{u(i)}) }
func fb(i uint64) []byte     { return privacy.FieldBytes(fe(i)) }

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func addr(b byte) (string, []byte) {
	raw := make([]byte, 20)
	for i := range raw {
		raw[i] = b + byte(i)
	}
	s, err := addresscodec.NewBech32Codec("earth").BytesToString(raw)
	must(err)
	return s, raw
}

func transfer(seed uint64, fee, valueOut uint64, denomOut string) shieldedtypes.Transfer {
	return shieldedtypes.Transfer{
		Proof:       []byte{0xde, 0xad, byte(seed)},
		Root:        fb(seed),
		Nullifiers:  [][]byte{fb(seed + 1), fb(seed + 2), fb(seed + 3)},
		Commitments: [][]byte{fb(seed + 4), fb(seed + 5), fb(seed + 6)},
		Ciphertexts: [][]byte{[]byte(fmt.Sprintf("ct-%d-0", seed)), []byte(fmt.Sprintf("ct-%d-1", seed)), {}},
		Fee:         fee,
		ValueOut:    valueOut,
		DenomOut:    denomOut,
	}
}

func membership(seed uint64) personhoodtypes.Membership {
	return personhoodtypes.Membership{Proof: []byte{0xbe, 0xef, byte(seed)}, Root: fb(seed + 100), Nullifier: fb(seed + 101)}
}

type msgVec struct {
	TypeURL string `json:"type_url"`
	Proto   string `json:"proto"`
	Signal  string `json:"signal,omitempty"`
}

func main() {
	ac := addresscodec.NewBech32Codec("earth")
	out := map[string]any{"chain_id": chainID}

	// ---- Poseidon2 ---------------------------------------------------------
	var pm1 fr.Element
	pm1.SetInt64(-1)
	type hv struct {
		In  []string `json:"in"`
		Out string   `json:"out"`
	}
	var hashes []hv
	for n := 1; n <= 12; n++ {
		in := make([]fr.Element, n)
		ins := make([]string, n)
		for i := range in {
			in[i] = u(uint64(i*1000003 + n))
			if i == n-1 && n%3 == 0 {
				in[i] = pm1
			}
			ins[i] = hx(in[i])
		}
		hashes = append(hashes, hv{ins, hx(poseidon2.Hash(in))})
	}
	hashes = append(hashes, hv{[]string{}, hx(poseidon2.Hash(nil))})
	out["poseidon2"] = hashes

	// ---- tags and derivations -----------------------------------------------
	out["tags"] = map[string]string{
		"id": hx(privacy.TagID), "owner": hx(privacy.TagOwner), "leaf": hx(privacy.TagLeaf),
		"sn": hx(privacy.TagSN), "pc": hx(privacy.TagPC), "cm": hx(privacy.TagCM), "nf": hx(privacy.TagNF),
		"reg": hx(privacy.TagReg), "asset": hx(privacy.TagAsset), "signal": hx(privacy.TagSignal),
		"bytes": hx(privacy.TagBytes), "scope": hx(privacy.TagScope),
	}
	assets := map[string]string{}
	for _, d := range []string{"uerth", "uanml", "", "derth/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
		"unbond/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq/17",
		"ibc/27394FB092D2ECCD56123C74F36E4C1F926001CEADA9CA97EA622B25F41E5EB2"} {
		assets[d] = hx(privacy.AssetID(d))
	}
	out["asset_ids"] = assets
	bytesV := map[string]string{}
	for _, n := range []int{0, 1, 30, 31, 32, 61, 62, 63, 100} {
		b := make([]byte, n)
		for i := range b {
			b[i] = byte(i*7 + 1)
		}
		bytesV[hex.EncodeToString(b)] = hx(privacy.Bytes(b))
	}
	out["bytes"] = bytesV
	out["country"] = map[string]string{"DE": hx(privacy.CountryField("DE")), "FR": hx(privacy.CountryField("FR")), "": hx(privacy.CountryField("")), "de": hx(privacy.CountryField("de"))}

	idSecret, nk := fe(1001), fe(1002)
	rho, rcm := fe(1003), fe(1004)
	opk := privacy.OwnerPK(nk)
	pc := privacy.PC(opk, rho, rcm)
	out["derive"] = map[string]string{
		"id_secret": hx(idSecret), "nk": hx(nk), "rho": hx(rho), "rcm": hx(rcm),
		"idc":      hx(privacy.IDC(idSecret)),
		"owner_pk": hx(opk),
		"leaf":     hx(privacy.IdentityLeaf(privacy.IDC(idSecret), fe(1005), privacy.CountryField("DE"), 1_790_000_000)),
		"leaf_dsc": hx(fe(1005)),
		"sn":       hx(privacy.ScopeNullifier(idSecret, privacy.ClaimScope(20360))),
		"pc":       hx(pc),
		"cm":       hx(privacy.CM(privacy.AssetID("uanml"), 1_000_000, pc)),
		"nf":       hx(privacy.NF(nk, rho, 4_000_000_000)),
		"reg_none": hx(privacy.RegistrationBinding(privacy.IDC(idSecret), fe(1), fe(2), fr.Element{})),
	}
	out["scopes"] = map[string]string{
		"claim_20360":           hx(privacy.ClaimScope(20360)),
		"caretaker":             hx(privacy.CaretakerScope()),
		"referrer":              hx(privacy.ReferrerScope()),
		"proposal_5_0":          hx(privacy.ProposalScope(5, 0)),
		"proposal_5_1":          hx(privacy.ProposalScope(5, 1)),
		"removal_3":             hx(privacy.RemovalScope(3)),
		"propose_removal_2_100": hx(privacy.ProposeRemovalScope(2, 100)),
	}

	// ---- merkle -------------------------------------------------------------
	zeros := make([]string, merkle.Depth+1)
	for i := range zeros {
		zeros[i] = hx(merkle.Zero[i])
	}
	t := merkle.NewMem()
	roots := map[string]string{}
	for i := uint64(0); i < 37; i++ {
		_, err := t.Append(fe(5000 + i))
		must(err)
		if i == 0 || i == 1 || i == 2 || i == 15 || i == 16 || i == 36 {
			r, _ := t.Root()
			roots[fmt.Sprint(i+1)] = hx(r)
		}
	}
	paths := map[string][]string{}
	for _, idx := range []uint64{0, 5, 36} {
		sib, err := t.Path(idx)
		must(err)
		s := make([]string, len(sib))
		for i := range sib {
			s[i] = hx(sib[i])
		}
		paths[fmt.Sprint(idx)] = s
	}
	must(t.Update(5, fr.Element{}))
	zr, _ := t.Root()
	out["merkle"] = map[string]any{
		"zeros": zeros, "leaf_seed": 5000, "roots": roots, "paths": paths, "root_37_zeroed_5": hx(zr),
	}

	// ---- signals ------------------------------------------------------------
	cts := [3][]byte{[]byte("a"), []byte("bb"), nil}
	recvStr, recv := addr(1)
	out["signals"] = map[string]string{
		"transfer_send":     hx(privacy.TransferSignal(chainID, nil, cts, 0)),
		"transfer_unshield": hx(privacy.TransferSignal(chainID, recv, cts, 77)),
		"action":            hx(privacy.ActionSignal("/x.y.Msg", chainID, cts, [3]fr.Element{fe(1), fe(2), fe(3)}, u(9))),
		"multi":             hx(privacy.MultiSpendSignal("/x.y.Msg", chainID, [][3][]byte{cts, {[]byte("c"), nil, nil}}, [][3]fr.Element{{fe(1), fe(2), fe(3)}, {fe(4), fe(5), fe(6)}}, u(9))),
		"receiver":          recvStr,
	}

	// ---- msgs ---------------------------------------------------------------
	msgs := map[string]msgVec{}
	totalFees := map[string]string{}
	add := func(name string, m sdk.Msg) {
		if pm, ok := m.(shieldedtypes.PrivateMsg); ok {
			totalFees[name] = shieldedtypes.TotalFee(pm).String()
		}
		bz, err := proto.Marshal(m)
		must(err)
		v := msgVec{TypeURL: sdk.MsgTypeURL(m), Proto: hex.EncodeToString(bz)}
		if pm, ok := m.(shieldedtypes.PrivateMsg); ok {
			s, err := pm.Signal(chainID, ac)
			must(err)
			v.Signal = hx(s)
		}
		msgs[name] = v
	}
	affStr, affRaw := addr(50)
	_, _ = addr(0)
	add("transfer_send", &shieldedtypes.MsgTransfer{Transfer: transfer(10, 1500, 0, "")})
	add("transfer_unshield", &shieldedtypes.MsgTransfer{Transfer: transfer(20, 0, 5000, "uerth"), Receiver: recvStr, FeeFromOutput: 1000})
	senderStr, _ := addr(30)
	add("shield", &shieldedtypes.MsgShield{Sender: senderStr, Amount: sdk.NewCoin("uerth", math.NewInt(100000)), Pc: fb(31), Ciphertext: []byte("gas")})
	reg := &personhoodtypes.MsgRegister{
		Fee: transfer(40, 2000, 0, ""), Proof: []byte{1, 2, 3}, PublicSignals: []string{"250930", "12345", "678", "9"},
		SignatureAlgorithm: "lean_poa", DscDer: []byte{0x30, 0x03, 1, 2, 3}, Idc: fb(41), PcAnml: fb(42), CiphertextAnml: []byte("anml"),
		PcErth: fb(43), CiphertextErth: []byte("erth"), Affiliate: affStr,
	}
	add("register", reg)
	bind, err := reg.Binding(ac)
	must(err)
	reg0 := *reg
	reg0.Affiliate = ""
	add("register_no_affiliate", &reg0)
	bind0, err := reg0.Binding(ac)
	must(err)
	out["registration_binding"] = map[string]string{"with_affiliate": hx(bind), "affiliate_bytes_field": hx(privacy.Bytes(affRaw)), "none": hx(bind0)}
	add("claim_anml", &personhoodtypes.MsgClaimAnml{Fee: transfer(50, 2000, 0, ""), Membership: membership(50), Day: 20360, Pc: fb(51), Ciphertext: []byte("claim")})
	add("set_caretaker", &personhoodtypes.MsgSetCaretaker{Fee: transfer(60, 2000, 0, ""), Membership: membership(60),
		Percentages: []allocationtypes.AllocationWeight{{OptionId: 1, Percent: 60}, {OptionId: 7, Percent: 40}}, MaxActivation: 1_780_000_000})
	add("bind_referrer", &personhoodtypes.MsgBindReferrer{Fee: transfer(70, 2000, 0, ""), Membership: membership(70), Address: affStr, MaxActivation: 1_780_000_000})
	add("bind_referrer_clear", &personhoodtypes.MsgBindReferrer{Fee: transfer(71, 2000, 0, ""), Membership: membership(71), MaxActivation: 1_780_000_000})
	add("vote_proposal", &assemblytypes.MsgVoteProposal{Fee: transfer(80, 2000, 0, ""), Membership: membership(80), ProposalId: 5, Option: assemblytypes.VoteOption(1)})
	add("propose_removal", &assemblytypes.MsgProposeRemoval{Fee: transfer(81, 2000, 0, ""), Membership: membership(81), OptionId: 3})
	add("vote_removal", &assemblytypes.MsgVoteRemoval{Fee: transfer(82, 2000, 0, ""), Membership: membership(82), OptionId: 3, Option: assemblytypes.VoteOption(2)})
	val := "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
	add("delegate", &stakingtypes.MsgDelegate{Transfer: transfer(90, 2000, 500000, "uerth"), Validator: val, Pc: fb(91), Ciphertext: []byte("d")})
	add("undelegate", &stakingtypes.MsgUndelegate{Transfer: transfer(100, 2000, 400000, stakingtypes.DerthDenom(val)), Validator: val, Pc: fb(101), Ciphertext: []byte("u")})
	add("claim_unbonding", &stakingtypes.MsgClaimUnbonding{Transfer: transfer(110, 0, 400000, stakingtypes.UnbondDenom(val, 17)), Validator: val, Epoch: 17, Pc: fb(111), Ciphertext: []byte("c"), FeeFromOutput: 2000})
	opts := []*govv1.WeightedVoteOption{{Option: govv1.OptionYes, Weight: "0.7"}, {Option: govv1.OptionNo, Weight: "0.300000000000000000"}}
	add("stake_vote", &stakingtypes.MsgStakeVote{Transfer: transfer(120, 0, 400000, stakingtypes.DerthDenom(val)), ProposalId: 5, Validator: val, Options: opts, Pc: fb(121), Ciphertext: []byte("v"), FeeTransfer: transfer(130, 2000, 0, "")})
	splits := []allocationtypes.AllocationWeight{{OptionId: 2, Percent: 100}}
	add("lock_position", &stakingtypes.MsgLockPosition{Transfer: transfer(140, 0, 400000, stakingtypes.DerthDenom(val)), Validator: val, Splits: splits, Pubkey: append([]byte{2}, fb(141)...)})
	sig := make([]byte, 64)
	for i := range sig {
		sig[i] = byte(i)
	}
	add("update_position", &stakingtypes.MsgUpdatePosition{Transfer: transfer(150, 2000, 0, ""), PositionId: 9, Splits: splits, Signature: sig})
	add("unlock_position", &stakingtypes.MsgUnlockPosition{Transfer: transfer(160, 2000, 0, ""), PositionId: 9, Pc: fb(161), Ciphertext: []byte("x"), Signature: sig})
	add("position_vote", &stakingtypes.MsgPositionVote{Transfer: transfer(170, 2000, 0, ""), PositionId: 9, ProposalId: 5, Options: opts, Signature: sig})

	// x/dex note paths.
	add("note_swap", &dextypes.MsgNoteSwap{Transfer: transfer(180, 2000, 300000, "uanml"), DenomOut: "uerth", MinAmountOut: 123456, Pc: fb(181)})
	add("note_swap_fee_from_output", &dextypes.MsgNoteSwap{Transfer: transfer(190, 0, 300000, "uanml"), DenomOut: "uerth", MinAmountOut: 123456, Pc: fb(191), Ciphertext: []byte("s"), FeeFromOutput: 3000})
	add("note_swap_to_anml", &dextypes.MsgNoteSwap{Transfer: transfer(200, 2000, 300000, "uerth"), DenomOut: "uanml", MinAmountOut: 1, Pc: fb(201)})
	add("add_liquidity_shielded", &dextypes.MsgAddLiquidityShielded{Transfer: transfer(210, 0, 700000, "uanml"), ErthTransfer: transfer(220, 2500, 900000, "uerth"),
		PoolId: 1, Provider: senderStr, MinShares: "777", RefundPc: fb(211)})
	add("add_liquidity_shielded_no_min", &dextypes.MsgAddLiquidityShielded{Transfer: transfer(230, 2500, 700000, "uanml"), ErthTransfer: transfer(240, 0, 900000, "uerth"),
		PoolId: 1, Provider: affStr, RefundPc: fb(231), RefundCiphertext: []byte("r")})
	add("remove_liquidity_pc", &dextypes.MsgRemoveLiquidity{Creator: senderStr, PoolId: 1, Shares: sdk.NewCoin("dexlp/1", math.NewInt(4242)), Pc: fb(250)})
	add("buy_anml", &dextypes.MsgBuyAnml{Creator: senderStr, TokenIn: sdk.NewCoin("uerth", math.NewInt(5000000)), MinAmountOut: "99", Pc: fb(260)})
	out["msgs"] = msgs
	out["validator"] = val
	out["derth_denom"] = stakingtypes.DerthDenom(val)
	out["unbond_denom_17"] = stakingtypes.UnbondDenom(val, 17)
	out["options_bytes"] = hex.EncodeToString(stakingtypes.OptionsBytes(opts))
	out["splits_bytes"] = hex.EncodeToString(stakingtypes.SplitsBytes([]allocationtypes.AllocationWeight{{OptionId: 1, Percent: 60}, {OptionId: 7, Percent: 40}}))
	out["position_sign_bytes"] = hex.EncodeToString(stakingtypes.PositionSignBytes(chainID, "vote", 9, 3,
		append(binary.BigEndian.AppendUint64(nil, 5), stakingtypes.OptionsBytes(opts)...)))

	// ---- an unsigned private tx ---------------------------------------------
	claim := &personhoodtypes.MsgClaimAnml{Fee: transfer(50, 2000, 0, ""), Membership: membership(50), Day: 20360, Pc: fb(51), Ciphertext: []byte("claim")}
	anyMsg, err := codectypes.NewAnyWithValue(claim)
	must(err)
	body := &txtypes.TxBody{Messages: []*codectypes.Any{anyMsg}}
	authInfo := &txtypes.AuthInfo{Fee: &txtypes.Fee{Amount: sdk.NewCoins(sdk.NewCoin("uerth", math.NewIntFromUint64(claim.Fee.Fee))), GasLimit: 2_600_000}}
	bodyBz, err := proto.Marshal(body)
	must(err)
	authBz, err := proto.Marshal(authInfo)
	must(err)
	raw := &txtypes.TxRaw{BodyBytes: bodyBz, AuthInfoBytes: authBz}
	rawBz, err := proto.Marshal(raw)
	must(err)
	out["unsigned_tx"] = map[string]any{"msg": "claim_anml", "gas_limit": 2_600_000, "tx_raw": hex.EncodeToString(rawBz)}

	// Every private msg's total fee (types.TotalFee): what the tx declares.
	out["total_fees"] = totalFees

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	must(enc.Encode(out))
}
