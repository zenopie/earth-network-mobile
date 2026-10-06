// Command privacyvectors prints golden vectors from the chain's own code for
// the Android and iOS privacy cores (android/app/src/test/resources/privacy/
// vectors.json, copied to iOS by gen.sh): Poseidon2, the zk/privacy derivations (the pool's and the
// stake tree's), scopes, the zk/merkle tree, zk/orchard (Grumpkin, hash to
// curve, value commitments, the binding signature, the bundle digest and
// sighash), every private msg's proto encoding and sighash, and an unsigned
// private tx. Run it through gen.sh, which points a throwaway module at a
// committed chain ref; nothing here has a second definition to drift from.
package main

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"os"

	"cosmossdk.io/math"
	"github.com/consensys/gnark-crypto/ecc/bn254/fr"
	gfr "github.com/consensys/gnark-crypto/ecc/grumpkin/fr"
	addresscodec "github.com/cosmos/cosmos-sdk/codec/address"
	codectypes "github.com/cosmos/cosmos-sdk/codec/types"
	sdk "github.com/cosmos/cosmos-sdk/types"
	authtypes "github.com/cosmos/cosmos-sdk/x/auth/types"
	txtypes "github.com/cosmos/cosmos-sdk/types/tx"
	govv1 "github.com/cosmos/cosmos-sdk/x/gov/types/v1"
	"github.com/cosmos/gogoproto/proto"

	allocationtypes "github.com/earth-network/earth/x/allocation/types"
	assemblytypes "github.com/earth-network/earth/x/assembly/types"
	dextypes "github.com/earth-network/earth/x/dex/types"
	personhoodtypes "github.com/earth-network/earth/x/personhood/types"
	shieldedtypes "github.com/earth-network/earth/x/shielded/types"
	stakingtypes "github.com/earth-network/earth/x/shieldedstaking/types"
	"github.com/earth-network/earth/zk/debt"
	"github.com/earth-network/earth/zk/indexed"
	"github.com/earth-network/earth/zk/merkle"
	"github.com/earth-network/earth/zk/orchard"
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

func pt(p orchard.Point) map[string]string {
	x, y := orchard.XY(p)
	return map[string]string{"x": hx(x), "y": hx(y)}
}

func scalarHex(s gfr.Element) string {
	b := s.Bytes()
	return hex.EncodeToString(b[:])
}

// bundle is a well-formed (unproven) bundle: two actions with real curve
// points as cvs, and the given balances.
func bundle(seed uint64, bal ...shieldedtypes.ValueBalance) shieldedtypes.Bundle {
	b := shieldedtypes.Bundle{Balances: bal, BindingSig: bytes.Repeat([]byte{byte(seed)}, orchard.BindingSigSize)}
	for i := uint64(0); i < 2; i++ {
		s := seed*10 + i
		cv := orchard.ValueCommit(privacy.AssetID("uerth"), 1000+s, privacy.AssetID("uanml"), s, fe(s+500))
		b.Actions = append(b.Actions, shieldedtypes.Action{
			Anchor: fb(s + 1), Nullifier: fb(s + 2), Commitment: fb(s + 3), Cv: orchard.PointBytes(cv),
			Ciphertext: []byte(fmt.Sprintf("ct-%d", s)), Proof: []byte{0xde, 0xad, byte(s)},
		})
	}
	return b
}

func fee(seed, amount uint64) shieldedtypes.Bundle {
	return bundle(seed, shieldedtypes.ValueBalance{Denom: "uerth", Amount: amount})
}

func membership(seed uint64) personhoodtypes.Membership {
	return personhoodtypes.Membership{Proof: []byte{0xbe, 0xef, byte(seed)}, Root: fb(seed + 100), Nullifier: fb(seed + 101)}
}

func moveProof(seed uint64) personhoodtypes.MoveProof {
	return personhoodtypes.MoveProof{Proof: []byte{0x30, 0x7e, byte(seed)}, Root: fb(seed + 100), OldNullifier: fb(seed + 101), NewNullifier: fb(seed + 102)}
}

// stakeProof is a well-formed stake proof: spends lane A nullifiers (one or
// two), creates lane A's output, credits fills the credit lane, clears names
// a clear_before and debt root. Ciphertexts are 201-byte stand-ins.
func stakeProof(seed uint64, spends int, creates, credits, clears bool) stakingtypes.StakeProof {
	zero := make([]byte, 32)
	p := stakingtypes.StakeProof{Proof: []byte{0x5e, byte(seed)}, Anchor: fb(seed), OwnerTag: fb(seed + 8),
		Commitment: zero, CreditNullifier: zero, CreditCommitment: zero, DebtRoot: zero}
	for i := 0; i < 2; i++ {
		nf := zero
		if i < spends {
			nf = fb(seed + 1 + uint64(i))
		}
		p.Nullifiers = append(p.Nullifiers, nf)
	}
	if creates {
		p.Commitment, p.Ciphertext = fb(seed+3), sct(byte(seed))
	}
	if credits {
		p.CreditNullifier, p.CreditCommitment, p.CreditCiphertext = fb(seed+4), fb(seed+5), sct(byte(seed+1))
	}
	if clears {
		p.ClearBefore, p.DebtRoot = 1_790_000_000+seed, fb(seed+6)
	}
	return p
}

// sct is a deterministic 201-byte stand-in for a wallet stake ciphertext.
func sct(seed byte) []byte {
	b := make([]byte, privacy.WalletStakeCiphertextBytes)
	for i := range b {
		b[i] = seed ^ byte(i)
	}
	return b
}

type msgVec struct {
	TypeURL       string `json:"type_url"`
	Proto         string `json:"proto"`
	Sighash       string `json:"sighash,omitempty"`
	TotalFee      string `json:"total_fee,omitempty"`
	PrivateFee    string `json:"private_fee,omitempty"`
	Memo          string `json:"memo"`
	TimeoutHeight uint64 `json:"timeout_height"`
	GasLimit      uint64 `json:"gas_limit"`
}

// bct is a deterministic 177-byte stand-in for an amount-blind ciphertext.
func bct(seed byte) []byte {
	b := make([]byte, privacy.BlindNoteCiphertextBytes)
	for i := range b {
		b[i] = seed + byte(i)
	}
	return b
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
		"id": hx(privacy.TagID), "owner": hx(privacy.TagOwner), "leaf": hx(privacy.TagLeaf), "succ": hx(privacy.TagSucc),
		"sn": hx(privacy.TagSN), "pc": hx(privacy.TagPC), "cm": hx(privacy.TagCM), "nf": hx(privacy.TagNF),
		"reg": hx(privacy.TagReg), "asset": hx(privacy.TagAsset), "signal": hx(privacy.TagSignal),
		"bytes": hx(privacy.TagBytes), "scope": hx(privacy.TagScope), "affiliate": hx(privacy.TagAffiliate), "referral": hx(privacy.TagReferral),
		"stake": hx(privacy.TagStake), "spc": hx(privacy.TagSPC), "snf": hx(privacy.TagSNF), "otag": hx(privacy.TagOTag),
		"snfl": hx(privacy.TagSNFL), "vnf": hx(privacy.TagVNF), "slabel": hx(privacy.TagSLabel), "debtl": hx(privacy.TagDebtL),
		"gen": hx(orchard.TagGen), "cv_r": hx(orchard.TagCvR), "bsig": hx(orchard.TagBsig), "bundle": hx(orchard.TagBundle),
	}
	val := "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
	denoms := []string{"uerth", "uanml", "", stakingtypes.DerthDenom(val), "dexlp/1",
		"ibc/27394FB092D2ECCD56123C74F36E4C1F926001CEADA9CA97EA622B25F41E5EB2"}
	assets := map[string]string{}
	for _, d := range denoms {
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
	spc := privacy.StakePC(opk, rho, rcm)
	out["derive"] = map[string]string{
		"id_secret": hx(idSecret), "nk": hx(nk), "rho": hx(rho), "rcm": hx(rcm),
		"idc":       hx(privacy.IDC(idSecret)),
		"owner_pk":  hx(opk),
		"leaf":      hx(privacy.IdentityLeaf(privacy.IDC(idSecret), fe(1005), privacy.CountryField("DE"), 1_790_000_000, 0)),
		"leaf_pred": hx(privacy.IdentityLeaf(privacy.IDC(idSecret), fe(1005), privacy.CountryField("DE"), 1_790_000_000, 1_790_000_000)),
		"leaf_dsc":  hx(fe(1005)),
		// The succession leaf a switch from id_secret's identity to fe(1006)'s appends (circuits/move).
		"succession_new_secret": hx(fe(1006)),
		"succession": hx(privacy.SuccessionLeaf(privacy.IDC(idSecret), privacy.IDC(fe(1006)))),
		"sn":        hx(privacy.ScopeNullifier(idSecret, privacy.ClaimScope(20360))),
		"pc":        hx(pc),
		"cm":        hx(privacy.CM(privacy.AssetID("uanml"), 1_000_000, pc)),
		"nf":        hx(privacy.NF(nk, rho, 4_000_000_000)),
		"reg_none":  hx(privacy.RegistrationBinding(chainID, privacy.IDC(idSecret), fe(1), []byte("anml"), fe(2), []byte("erth"), fr.Element{})),
		// The chain's own pin (zk/privacy TestRegistrationBindingPinned, audit 6 B6-4).
		"reg_pinned": hx(privacy.RegistrationBinding("earth-1", u(1), u(2), []byte("anml"), u(3), []byte("erth"), fr.Element{})),
		"reg_testnet": hx(privacy.RegistrationBinding("earth-testnet-1", u(1), u(2), []byte("anml"), u(3), []byte("erth"), fr.Element{})),
		"spc":       hx(spc),
		"stake_cm":  hx(privacy.StakeCM(privacy.AssetID(stakingtypes.DerthDenom(val)), 1_800_000, spc, fr.Element{})),
		"stake_label": hx(privacy.StakeLabel(fe(1009), 1_790_000_000, 400_000)),
		"stake_cm_labelled": hx(privacy.StakeCM(privacy.AssetID(stakingtypes.DerthDenom(val)), 1_800_000, spc, privacy.StakeLabel(fe(1009), 1_790_000_000, 400_000))),
		"debt_leaf": hx(privacy.DebtLeaf(fe(1010), fe(1011), 4_000_000_000, 123_456)),
		// zk/debt TestNoirParity's pins (= privacy_core test_go_parity_debt).
		"debt_leaf_1_2_3_4": hx(privacy.DebtLeaf(u(1), u(2), 3, 4)),
		"stake_label_4d4b": hx(privacy.StakeLabel(u(0x4d4b), 1000, 200)),
		"stake_cm_1_2_3_4": hx(privacy.StakeCM(u(1), 2, u(3), u(4))),
		"stake_nf":  hx(privacy.StakeNF(nk, rho, 4_000_000_000)),
		"otag_salt": hx(fe(1006)),
		"otag":      hx(privacy.OwnerTag(opk, fe(1006))),
		"nf_leaf_1_2_3": hx(privacy.NFLeaf(u(1), u(2), 3)),
		"nf_leaf":   hx(privacy.NFLeaf(fe(1007), fe(1008), 4_000_000_000)),
		"vote_nf":   hx(privacy.VoteNF(nk, rho, 4_000_000_000, 5)),
		"vote_nf_5eed": hx(privacy.VoteNF(u(0x5eed), u(0xa1), 1, 7)),
		"vote_pad_nf":  hx(privacy.VotePadNF(nk, rho, 5)),
		"vote_pad_nf_5eed": hx(privacy.VotePadNF(u(0x5eed), u(0x77), 7)),
	}
	out["scopes"] = map[string]string{
		"claim_20360":           hx(privacy.ClaimScope(20360)),
		"caretaker":             hx(privacy.CaretakerScope()),
		"handle":                hx(privacy.HandleScope()),
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
	// ---- the stake nullifier indexed tree (zk/indexed) ----------------------
	{
		it := indexed.NewMem()
		values := []string{}
		iroots := map[string]string{}
		r0, _ := it.Root()
		iroots["0"] = hx(r0)
		for i := uint64(0); i < 21; i++ {
			v := fe(7000 + i)
			_, err := it.Insert(v)
			must(err)
			values = append(values, hx(v))
			if i == 0 || i == 1 || i == 4 || i == 20 {
				r, _ := it.Root()
				iroots[fmt.Sprint(i+1)] = hx(r)
			}
		}
		type wit struct {
			Value        string   `json:"value"`
			LowValue     string   `json:"low_value"`
			LowNextValue string   `json:"low_next_value"`
			LowNextIndex uint64   `json:"low_next_index"`
			LowIndex     uint64   `json:"low_index"`
			LowPath      []string `json:"low_path"`
		}
		wits := []wit{}
		root, _ := it.Root()
		for _, v := range []fr.Element{fe(8000), fe(8001), fe(8002), u(1)} {
			w, err := it.NonMembership(v)
			must(err)
			if !w.Verify(v, root) {
				panic("non-membership witness does not verify")
			}
			p := make([]string, len(w.Path))
			for i := range w.Path {
				p[i] = hx(w.Path[i])
			}
			wits = append(wits, wit{hx(v), hx(w.Low.Value), hx(w.Low.NextValue), w.Low.NextIndex, w.Index, p})
		}
		out["indexed"] = map[string]any{
			"empty_root": hx(indexed.EmptyRoot), "values": values, "roots": iroots, "witnesses": wits,
		}
	}

	// ---- the slash debt tree (zk/debt) --------------------------------------
	{
		type wit struct {
			Key          string   `json:"key"`
			Exposed      uint64   `json:"exposed"`
			Retained     uint64   `json:"retained"`
			LowKey       string   `json:"low_key"`
			LowNextKey   string   `json:"low_next_key"`
			LowNextIndex uint64   `json:"low_next_index"`
			LowRetained  uint64   `json:"low_retained"`
			LowIndex     uint64   `json:"low_index"`
			LowPath      []string `json:"low_path"`
		}
		type row struct {
			Key      string `json:"key"`
			Retained uint64 `json:"retained"`
		}
		var rows []debt.Row
		var rowsJ []row
		droots := map[string]string{"0": hx(debt.EmptyRoot)}
		for i := uint64(0); i < 9; i++ {
			rows = append(rows, debt.Row{Key: fe(9000 + i), Retained: 1_000 * (i + 1)})
		}
		// A later slash rewrites row 2's retained in place (insertion order kept).
		rows = append(rows, debt.Row{Key: fe(9002), Retained: 7})
		dt := debt.NewMem()
		for i, r := range rows {
			_, err := dt.Set(r.Key, r.Retained)
			must(err)
			rr, _ := dt.Root()
			droots[fmt.Sprint(i+1)] = hx(rr)
		}
		final := map[fr.Element]uint64{}
		var order []fr.Element
		for _, r := range rows {
			if _, ok := final[r.Key]; !ok {
				order = append(order, r.Key)
			}
			final[r.Key] = r.Retained
		}
		for _, k := range order {
			rowsJ = append(rowsJ, row{hx(k), final[k]})
		}
		root, _ := dt.Root()
		var wits []wit
		for _, k := range []fr.Element{fe(9002), fe(9005), fe(9500), fe(9501), u(1)} {
			w, err := dt.Lookup(k)
			must(err)
			r, ok := w.Retained(k, 50_000, root)
			if !ok {
				panic("debt witness does not verify")
			}
			p := make([]string, len(w.Path))
			for i := range w.Path {
				p[i] = hx(w.Path[i])
			}
			wits = append(wits, wit{hx(k), 50_000, r, hx(w.Low.Key), hx(w.Low.NextKey), w.Low.NextIndex, w.Low.Retained, w.Index, p})
		}
		out["debt"] = map[string]any{"empty_root": hx(debt.EmptyRoot), "roots_by_write": droots, "rows": rowsJ, "root": hx(root), "witnesses": wits}
	}

	must(t.Update(5, fr.Element{}))
	zr, _ := t.Root()
	out["merkle"] = map[string]any{
		"zeros": zeros, "leaf_seed": 5000, "roots": roots, "paths": paths, "root_37_zeroed_5": hx(zr),
	}

	// ---- zk/orchard -----------------------------------------------------------
	type h2c struct {
		Tag   string            `json:"tag"`
		Input string            `json:"input"`
		Ctr   uint32            `json:"ctr"`
		Point map[string]string `json:"point"`
		// Misses lists the counters below ctr that were not squares.
		Misses []uint32 `json:"misses"`
	}
	var h2cs []h2c
	for i, in := range []fr.Element{privacy.AssetID("uerth"), privacy.AssetID("uanml"), privacy.AssetID(stakingtypes.DerthDenom(val)),
		privacy.AssetID("dexlp/1"), fe(1), fe(2), fe(3), fe(4), fe(5), {}} {
		tg := orchard.TagGen
		if i == 9 {
			tg = orchard.TagCvR
		}
		p, ctr := orchard.HashToPoint(tg, in)
		var miss []uint32
		for c := uint32(0); c < ctr; c++ {
			if _, ok := orchard.HashToPointAt(tg, in, c); !ok {
				miss = append(miss, c)
			}
		}
		h2cs = append(h2cs, h2c{hx(tg), hx(in), ctr, pt(p), miss})
	}
	bases := map[string]map[string]string{}
	for _, d := range denoms {
		bases[d] = pt(orchard.ValueBase(privacy.AssetID(d)))
	}
	sc := func(i uint64) gfr.Element { return orchard.ScalarFromField(fe(i)) }
	var nm1 gfr.Element
	nm1.SetInt64(-1)
	g := orchard.ValueBase(privacy.AssetID("uerth"))
	h := orchard.ValueBase(privacy.AssetID("uanml"))
	type mulV struct {
		Scalar string            `json:"scalar"`
		Point  map[string]string `json:"point"`
		Out    map[string]string `json:"out"`
	}
	var muls []mulV
	for _, s := range []gfr.Element{sc(1), sc(2), nm1, orchard.ScalarU64(1), orchard.ScalarU64(2), orchard.ScalarU64(^uint64(0)), {}} {
		muls = append(muls, mulV{scalarHex(s), pt(g), pt(orchard.Mul(g, s))})
	}
	out["orchard"] = map[string]any{
		"r":          pt(orchard.R),
		"r_ctr":      orchard.RCounter,
		"n":          gfr.Modulus().String(),
		"h2c":        h2cs,
		"bases":      bases,
		"add":        map[string]any{"a": pt(g), "b": pt(h), "sum": pt(orchard.Add(g, h)), "dbl": pt(orchard.Add(g, g)), "a_minus_b": pt(orchard.Sub(g, h)), "a_minus_a": pt(orchard.Sub(g, g))},
		"mul":        muls,
		"value_commit": func() []map[string]string {
			type vc struct {
				sa, oa         string
				sv, ov         uint64
				rcv            fr.Element
			}
			var pm1f fr.Element
			pm1f.SetInt64(-1)
			var outs []map[string]string
			for _, c := range []vc{
				{"uerth", "uerth", 1_000_000, 990_000, fe(9001)},
				{"uerth", "uanml", 1_000_000, 500, fe(9002)},
				{"uanml", "uerth", 0, 7, fe(9003)},
				{"uanml", "uanml", 0, 0, fe(9004)},
				{"uerth", "uerth", ^uint64(0), ^uint64(0), fr.Element{}},
				{"uerth", "uanml", 5, 0, pm1f},
			} {
				outs = append(outs, map[string]string{
					"s_denom": c.sa, "o_denom": c.oa, "s_value": fmt.Sprint(c.sv), "o_value": fmt.Sprint(c.ov), "rcv": hx(c.rcv),
					"cv": hex.EncodeToString(orchard.PointBytes(orchard.ValueCommit(privacy.AssetID(c.sa), c.sv, privacy.AssetID(c.oa), c.ov, c.rcv))),
				})
			}
			return outs
		}(),
	}

	// Binding signature: a balanced bundle (ERTH 1_000_000 -> 990_000 out + 10_000 fee; ANML 700 -> 700).
	rcvs := []fr.Element{fe(7001), fe(7002), fe(7003)}
	ob := &orchard.Bundle{}
	specs := []struct {
		sa string
		sv uint64
		oa string
		ov uint64
	}{{"uerth", 1_000_000, "uanml", 700}, {"uanml", 700, "uerth", 990_000}, {"uerth", 0, "uerth", 0}}
	for i, s := range specs {
		ob.Actions = append(ob.Actions, orchard.Action{
			Anchor: fe(7100), Nullifier: fe(7110 + uint64(i)), Commitment: fe(7120 + uint64(i)),
			Cv:         orchard.ValueCommit(privacy.AssetID(s.sa), s.sv, privacy.AssetID(s.oa), s.ov, rcvs[i]),
			Ciphertext: []byte(fmt.Sprintf("bsig-ct-%d", i)),
		})
	}
	ob.Balances = []orchard.Balance{{Asset: privacy.AssetID("uerth"), Value: 10_000}}
	sighash := orchard.Sighash("/earth.shielded.v1.MsgSend", chainID, orchard.TxFields{}, []*orchard.Bundle{ob}, privacy.Bytes(nil), u(10_000))
	sighashTx := orchard.Sighash("/earth.shielded.v1.MsgSend", chainID, orchard.TxFields{Memo: "deposit 42 ü", TimeoutHeight: 123456, GasLimit: 2_600_000},
		[]*orchard.Bundle{ob}, privacy.Bytes(nil), u(10_000))
	bsk := orchard.BindingSigningKey(rcvs)
	sig0, err := orchard.SignBinding(bsk, sighash, bytes.NewReader(make([]byte, 32)))
	must(err)
	rnd1 := make([]byte, 32)
	for i := range rnd1 {
		rnd1[i] = byte(i + 1)
	}
	sig1, err := orchard.SignBinding(bsk, sighash, bytes.NewReader(rnd1))
	must(err)
	ob.BindingSig = sig0
	must(ob.CheckBalance(sighash, orchard.CanonicalBase))
	bvk, err := ob.BindingKey(orchard.CanonicalBase)
	must(err)
	var acts []map[string]string
	for i, a := range ob.Actions {
		acts = append(acts, map[string]string{
			"anchor": hx(a.Anchor), "nf": hx(a.Nullifier), "cm": hx(a.Commitment),
			"cv": hex.EncodeToString(orchard.PointBytes(a.Cv)), "ct": hex.EncodeToString(a.Ciphertext),
			"s_denom": specs[i].sa, "s_value": fmt.Sprint(specs[i].sv), "o_denom": specs[i].oa, "o_value": fmt.Sprint(specs[i].ov), "rcv": hx(rcvs[i]),
		})
	}
	out["binding"] = map[string]any{
		"actions":  acts,
		"balances": []map[string]any{{"denom": "uerth", "value": 10_000}},
		"digest":   hx(ob.Digest()),
		"sighash":  hx(sighash),
		"sighash_tx": map[string]any{"memo": "deposit 42 ü", "timeout_height": 123456, "gas_limit": 2_600_000, "sighash": hx(sighashTx)},
		"bsk":      scalarHex(bsk),
		"bvk":      pt(bvk),
		"sig_rnd0": hex.EncodeToString(sig0),
		"rnd1":     hex.EncodeToString(rnd1),
		"sig_rnd1": hex.EncodeToString(sig1),
		"bsk_wrap": func() string {
			// Σrcv past n: rcvs of p-1 each, enough to wrap.
			var pm1f fr.Element
			pm1f.SetInt64(-1)
			s := orchard.BindingSigningKey([]fr.Element{pm1f, pm1f, pm1f})
			return scalarHex(s)
		}(),
	}
	_ = big.NewInt

	// ---- msgs ---------------------------------------------------------------
	msgs := map[string]msgVec{}
	txf := orchard.TxFields{GasLimit: 2_600_000}
	addTx := func(name string, m sdk.Msg, tx orchard.TxFields) {
		bz, err := proto.Marshal(m)
		must(err)
		v := msgVec{TypeURL: sdk.MsgTypeURL(m), Proto: hex.EncodeToString(bz), Memo: tx.Memo, TimeoutHeight: tx.TimeoutHeight, GasLimit: tx.GasLimit}
		if pm, ok := m.(shieldedtypes.PrivateMsg); ok {
			s, err := shieldedtypes.Sighash(pm, chainID, tx, ac)
			must(err)
			v.Sighash = hx(s)
			v.TotalFee = shieldedtypes.TotalFee(pm).String()
			v.PrivateFee = fmt.Sprint(pm.PrivateFee())
		}
		msgs[name] = v
	}
	add := func(name string, m sdk.Msg) { addTx(name, m, txf) }
	recvStr, _ := addr(1)
	// A shielded address for handles and referral notes (the blind golden keys': ek 01..20, owner_pk OwnerPK(7)).
	var gek [32]byte
	for i := range gek {
		gek[i] = byte(i + 1)
	}
	gekPub, err := privacy.EKPub(gek)
	must(err)
	zaddr := privacy.ShieldedAddress{OwnerPK: privacy.OwnerPK(u(7)), EKPub: gekPub}.Encode()
	senderStr, _ := addr(30)
	add("send", &shieldedtypes.MsgSend{Bundle: fee(10, 1500), Fee: 1500})
	addTx("send_tx_fields", &shieldedtypes.MsgSend{Bundle: fee(11, 1500), Fee: 1500},
		orchard.TxFields{Memo: "deposit 42 ü", TimeoutHeight: 123456, GasLimit: 2_600_000})
	addTx("send_no_gas", &shieldedtypes.MsgSend{Bundle: fee(12, 1500), Fee: 1500}, orchard.TxFields{})
	add("unshield", &shieldedtypes.MsgSend{Bundle: bundle(20, shieldedtypes.ValueBalance{Denom: "uanml", Amount: 5000}, shieldedtypes.ValueBalance{Denom: "uerth", Amount: 2000}),
		Receiver: recvStr, Fee: 2000})
	add("shield", &shieldedtypes.MsgShield{Sender: senderStr, Amount: sdk.NewCoin("uerth", math.NewInt(100000)), Pc: fb(31), Ciphertext: bct(31)})
	reg := &personhoodtypes.MsgRegister{
		Fee: fee(40, 2000), Proof: []byte{1, 2, 3}, PublicSignals: []string{"250930", "12345", "678", "9"},
		SignatureAlgorithm: "lean_poa", DscDer: []byte{0x30, 0x03, 1, 2, 3}, Idc: fb(41), PcAnml: fb(42), CiphertextAnml: bct(42),
		PcErth: fb(43), CiphertextErth: bct(43),
		AffiliateHandle: "alice-01",
	}
	add("register", reg)
	bind, err := reg.Binding(ac, chainID)
	must(err)
	reg0 := *reg
	reg0.AffiliateHandle = ""
	add("register_no_affiliate", &reg0)
	bind0, err := reg0.Binding(ac, chainID)
	must(err)
	out["registration_binding"] = map[string]string{"with_affiliate": hx(bind), "affiliate_field": hx(privacy.AffiliateField("alice-01")), "none": hx(bind0)}
	// The referral note's opening (chain 203d3b2: the chain mints it to the handle's owner_pk).
	type refV struct {
		Nullifier string `json:"nullifier"`
		LeafIndex uint64 `json:"leaf_index"`
		Rho       string `json:"rho"`
		Rcm       string `json:"rcm"`
		OwnerPk   string `json:"owner_pk"`
		Pc        string `json:"pc"`
		Cm        string `json:"cm"`
	}
	var refs []refV
	for i, c := range []struct {
		nf   fr.Element
		leaf uint64
	}{{fe(7001), 0}, {fe(7002), 1}, {fe(7003), 41}, {fe(7004), 1 << 32}, {fe(7005), 1 << 40}} {
		r0, r1 := privacy.ReferralOpening(c.nf, c.leaf)
		owner := privacy.OwnerPK(fe(uint64(7100 + i)))
		pc := privacy.PC(owner, r0, r1)
		refs = append(refs, refV{hx(c.nf), c.leaf, hx(r0), hx(r1), hx(owner), hx(pc), hx(privacy.CM(privacy.AssetID("uerth"), 5_000_000, pc))})
	}
	out["referral_opening"] = refs
	add("claim_anml", &personhoodtypes.MsgClaimAnml{Fee: fee(50, 2000), Membership: membership(50), Day: 20360, Pc: fb(51), Ciphertext: bct(51)})
	add("set_caretaker", &personhoodtypes.MsgSetCaretaker{Fee: fee(60, 2000), Membership: membership(60),
		Percentages: []allocationtypes.AllocationWeight{{OptionId: 1, Percent: 60}, {OptionId: 7, Percent: 40}}, MaxPredecessor: 1_750_000_000})
	add("set_caretaker_no_bound", &personhoodtypes.MsgSetCaretaker{Fee: fee(61, 2000), Membership: membership(61),
		Percentages: []allocationtypes.AllocationWeight{{OptionId: 2, Percent: 100}}, MaxPredecessor: uint64(personhoodtypes.NoBound)})
	add("move_caretaker", &personhoodtypes.MsgMoveCaretaker{Fee: fee(62, 2000), Move: moveProof(62)})
	add("bind_handle", &personhoodtypes.MsgBindHandle{Fee: fee(70, 2000), Membership: membership(70), Handle: "alice-01", Address: zaddr, MaxPredecessor: 1_750_000_000})
	add("bind_handle_release", &personhoodtypes.MsgBindHandle{Fee: fee(71, 2000), Membership: membership(71), MaxPredecessor: uint64(personhoodtypes.NoBound)})
	add("move_handle", &personhoodtypes.MsgMoveHandle{Fee: fee(72, 2000), Move: moveProof(72), Handle: "alice-01"})
	out["handle_address"] = zaddr
	add("vote_proposal", &assemblytypes.MsgVoteProposal{Fee: fee(80, 2000), Membership: membership(80), ProposalId: 5, Option: assemblytypes.VoteOption(1)})
	add("propose_removal", &assemblytypes.MsgProposeRemoval{Fee: fee(81, 2000), Membership: membership(81), OptionId: 3})
	add("vote_removal", &assemblytypes.MsgVoteRemoval{Fee: fee(82, 2000), Membership: membership(82), OptionId: 3, Option: assemblytypes.VoteOption(2)})

	// Chain dff3a9b: the proof merges the credited derth into the owner's note (a padding input when it holds none).
	add("delegate", &stakingtypes.MsgDelegate{Bundle: fee(90, 502000), Validator: val, Amount: 500000, Derth: 449_995, Stake: stakeProof(90, 1, true, false, true)})
	add("delegate_young", &stakingtypes.MsgDelegate{Bundle: fee(91, 502000), Validator: val, Amount: 500000, Derth: 449_995, Stake: stakeProof(91, 1, true, false, false)})
	add("restake", &stakingtypes.MsgRestake{Bundle: fee(95, 2000), Validator: val, Stake: stakeProof(95, 2, true, false, true)})
	// The undelegation names its payout (pc 6, ciphertext 7); the proof's output is the change or a zero note.
	add("undelegate", &stakingtypes.MsgUndelegate{Bundle: fee(100, 2000), Validator: val, Amount: 400000, Stake: stakeProof(100, 1, true, false, true), Pc: fb(101), Ciphertext: bct(101)})
	add("undelegate_whole", &stakingtypes.MsgUndelegate{Bundle: fee(102, 2000), Validator: val, Amount: 400000, Stake: stakeProof(102, 2, true, false, true), Pc: fb(103), Ciphertext: bct(103)})
	// Wave 3 (F3): canonical LegacyDec weights only.
	opts := []*govv1.WeightedVoteOption{{Option: govv1.OptionYes, Weight: "0.700000000000000000"}, {Option: govv1.OptionNo, Weight: "0.300000000000000000"}}
	// Two vote nullifiers, non-zero and distinct (an unused slot carries a padding nullifier); the current debt root.
	add("stake_vote", &stakingtypes.MsgStakeVote{Bundle: fee(120, 2000), ProposalId: 5, Validator: val, Options: opts, Weight: 400000, Proof: []byte{0x70, 0x7e},
		VoteNullifiers: [][]byte{fb(121), fb(122)}, DebtRoot: privacy.FieldBytes(debt.EmptyRoot)})
	add("stake_vote_two", &stakingtypes.MsgStakeVote{Bundle: fee(127, 2000), ProposalId: 6, Validator: val, Options: opts, Weight: 999, Proof: []byte{0x70, 0x7e},
		VoteNullifiers: [][]byte{fb(128), fb(129)}, DebtRoot: fb(130)})
	// RoundVoteWeight (C-L3): three significant digits, rounded down.
	rw := map[string]string{}
	for _, w := range []uint64{1, 999, 1000, 1009, 123_456, 399_999_999, 1_000_000_000_000, 18_446_744_073_709_551_615, 9_223_372_036_854_775_807} {
		rw[fmt.Sprint(w)] = fmt.Sprint(stakingtypes.RoundVoteWeight(w))
	}
	out["round_vote_weight"] = rw
	splits := []allocationtypes.AllocationWeight{{OptionId: 2, Percent: 100}}
	add("lock_position", &stakingtypes.MsgLockPosition{Bundle: fee(140, 2000), Validator: val, Amount: 400000, Splits: splits, Stake: stakeProof(140, 1, true, false, true)})
	add("update_position", &stakingtypes.MsgUpdatePosition{Bundle: fee(150, 2000), PositionId: 9, Splits: splits, Stake: stakeProof(150, 0, false, false, true)})
	add("unlock_position", &stakingtypes.MsgUnlockPosition{Bundle: fee(160, 2000), PositionId: 9, Stake: stakeProof(160, 1, true, false, true)})
	add("position_vote", &stakingtypes.MsgPositionVote{Bundle: fee(170, 2000), PositionId: 9, ProposalId: 5, Options: opts, Stake: stakeProof(170, 0, false, false, true)})
	val2raw := make([]byte, 20)
	for i := range val2raw {
		val2raw[i] = byte(i + 1)
	}
	val2, err := addresscodec.NewBech32Codec("earthvaloper").BytesToString(val2raw)
	must(err)
	add("redelegate", &stakingtypes.MsgRedelegate{Bundle: fee(175, 2000), SrcValidator: val, DstValidator: val2, Amount: 400000,
		Stake: stakeProof(175, 1, true, true, true), DstDerth: 380_000, MoveTime: 1_790_000_123})
	sp := stakeProof(175, 1, true, true, true)
	sf := sp.StakeFields()
	sfs := make([]string, len(sf))
	for i := range sf {
		sfs[i] = hx(sf[i])
	}
	out["stake_fields_redelegate"] = sfs
	// The circuits' public inputs as the chain lays them out (StakeProof.PublicInputs, VotePublicInputs).
	{
		pis := map[string][]string{}
		for name, m := range map[string]stakingtypes.StakeMsg{
			"delegate":   &stakingtypes.MsgDelegate{Validator: val, Amount: 500000, Derth: 449_995, Stake: stakeProof(90, 1, true, false, true)},
			"undelegate": &stakingtypes.MsgUndelegate{Validator: val, Amount: 400000, Stake: stakeProof(100, 1, true, false, true)},
			"redelegate": &stakingtypes.MsgRedelegate{SrcValidator: val, DstValidator: val2, Amount: 400000, Stake: stakeProof(175, 1, true, true, true), DstDerth: 380_000, MoveTime: 1_790_000_123},
		} {
			p := m.StakeProofOf()
			in := p.PublicInputs(m.StakeLanes(), fe(77))
			ss := make([]string, len(in))
			for i := range in {
				ss[i] = hex.EncodeToString(in[i])
			}
			pis[name] = ss
		}
		vm := &stakingtypes.MsgStakeVote{ProposalId: 6, Validator: val, Weight: 999, VoteNullifiers: [][]byte{fb(128), fb(129)}, DebtRoot: fb(130)}
		vin := vm.VotePublicInputs(fb(131), fb(132), fe(77))
		vs := make([]string, len(vin))
		for i := range vin {
			vs[i] = hex.EncodeToString(vin[i])
		}
		pis["stake_vote"] = vs
		out["public_inputs"] = pis
		out["validator2"] = val2
	}

	// x/dex note paths.
	add("note_swap", &dextypes.MsgNoteSwap{Bundle: bundle(180, shieldedtypes.ValueBalance{Denom: "uanml", Amount: 300000}, shieldedtypes.ValueBalance{Denom: "uerth", Amount: 2000}),
		DenomIn: "uanml", AmountIn: 300000, DenomOut: "uerth", MinAmountOut: 123456, Pc: fb(181), Ciphertext: bct(181)})
	add("note_swap_to_anml", &dextypes.MsgNoteSwap{Bundle: fee(200, 302000), DenomIn: "uerth", AmountIn: 300000, DenomOut: "uanml", MinAmountOut: 1, Pc: fb(201), Ciphertext: bct(201)})
	add("add_liquidity_shielded", &dextypes.MsgAddLiquidityShielded{Bundle: bundle(210, shieldedtypes.ValueBalance{Denom: "uanml", Amount: 700000}, shieldedtypes.ValueBalance{Denom: "uerth", Amount: 902500}),
		PoolId: 1, MinShares: "777", RefundPc: fb(211), RefundCiphertext: bct(211), ErthAmount: 900000, SharePc: fb(212), ShareCiphertext: bct(212)})
	add("add_liquidity_shielded_no_min", &dextypes.MsgAddLiquidityShielded{Bundle: bundle(230, shieldedtypes.ValueBalance{Denom: "uanml", Amount: 700000}, shieldedtypes.ValueBalance{Denom: "uerth", Amount: 902500}),
		PoolId: 1, RefundPc: fb(231), RefundCiphertext: bct(231), ErthAmount: 900000, SharePc: fb(232), ShareCiphertext: bct(232)})
	add("remove_liquidity_shielded", &dextypes.MsgRemoveLiquidityShielded{Bundle: bundle(240, shieldedtypes.ValueBalance{Denom: "dexlp/1", Amount: 4242}, shieldedtypes.ValueBalance{Denom: "uerth", Amount: 2000}),
		PoolId: 1, ErthPc: fb(241), ErthCiphertext: bct(241), TokenPc: fb(242), TokenCiphertext: bct(242)})
	add("remove_liquidity_pc", &dextypes.MsgRemoveLiquidity{Creator: senderStr, PoolId: 1, Shares: sdk.NewCoin("dexlp/1", math.NewInt(4242)), Pc: fb(250), Ciphertext: bct(250)})
	add("buy_anml", &dextypes.MsgBuyAnml{Creator: senderStr, TokenIn: sdk.NewCoin("uerth", math.NewInt(5000000)), MinAmountOut: "99", Pc: fb(260), Ciphertext: bct(4)})
	out["msgs"] = msgs
	out["validator"] = val
	out["derth_denom"] = stakingtypes.DerthDenom(val)
	out["options_bytes"] = hex.EncodeToString(stakingtypes.OptionsBytes(opts))
	out["splits_bytes"] = hex.EncodeToString(stakingtypes.SplitsBytes([]allocationtypes.AllocationWeight{{OptionId: 1, Percent: 60}, {OptionId: 7, Percent: 40}}))

	// ---- blind ciphertexts (chain goldenKeys: ek 01..20, esk 40..5f, owner_pk OwnerPK(7), rho 11, rcm 13, memo "golden memo")
	{
		var ek, esk [32]byte
		for i := range ek {
			ek[i] = byte(i + 1)
			esk[i] = byte(0x40 + i)
		}
		ekPub, err := privacy.EKPub(ek)
		must(err)
		bn := privacy.BlindNote{Rho: u(11), Rcm: u(13)}
		copy(bn.Memo[:], "golden memo")
		note, err := privacy.EncryptBlindNote(bn, ekPub, esk)
		must(err)
		opk := privacy.OwnerPK(u(7))
		spc := privacy.StakePC(opk, bn.Rho, bn.Rcm)
		out["blind"] = map[string]string{
			"ek": hex.EncodeToString(ek[:]), "esk": hex.EncodeToString(esk[:]), "ek_pub": hex.EncodeToString(ekPub[:]),
			"owner_pk": hx(opk), "rho": hx(bn.Rho), "rcm": hx(bn.Rcm), "memo": "golden memo",
			"note_ct": hex.EncodeToString(note),
			"spc": hx(spc), "stake_cm_derth_1800000": hx(privacy.StakeCM(privacy.AssetID(stakingtypes.DerthDenom(val)), 1_800_000, spc, fr.Element{})),
			"wallet_stake_ciphertext_bytes": fmt.Sprint(privacy.WalletStakeCiphertextBytes),
			// The wallet stake note golden's labelled cm: label (0x4d4b, 1000, 200).
			"stake_cm_derth_1800000_labelled": hx(privacy.StakeCM(privacy.AssetID(stakingtypes.DerthDenom(val)), 1_800_000, spc, privacy.StakeLabel(u(0x4d4b), 1000, 200))),
		}
	}

	// ---- an unsigned private tx ---------------------------------------------
	claim := &personhoodtypes.MsgClaimAnml{Fee: fee(50, 2000), Membership: membership(50), Day: 20360, Pc: fb(51), Ciphertext: bct(51)}
	anyMsg, err := codectypes.NewAnyWithValue(claim)
	must(err)
	body := &txtypes.TxBody{Messages: []*codectypes.Any{anyMsg}, Memo: "deposit 42 ü", TimeoutHeight: 123456}
	authInfo := &txtypes.AuthInfo{Fee: &txtypes.Fee{Amount: sdk.NewCoins(sdk.NewCoin("uerth", math.NewInt(2000))), GasLimit: 2_600_000}}
	bodyBz, err := proto.Marshal(body)
	must(err)
	authBz, err := proto.Marshal(authInfo)
	must(err)
	raw := &txtypes.TxRaw{BodyBytes: bodyBz, AuthInfoBytes: authBz}
	rawBz, err := proto.Marshal(raw)
	must(err)
	out["unsigned_tx"] = map[string]any{"msg": "claim_anml", "gas_limit": 2_600_000, "memo": "deposit 42 ü", "timeout_height": 123456, "tx_raw": hex.EncodeToString(rawBz)}

	// ---- membership public inputs (predecessor-aware, 4a663d5) ------------
	{
		m := membership(50)
		pis := personhoodtypes.MembershipPublicInputs(m, privacy.ClaimScope(20360), fe(9), fe(10), privacy.CountryField("FR"),
			uint64(personhoodtypes.NoBound), 1_750_000_000)
		ps := make([]string, len(pis))
		for i := range pis {
			ps[i] = hex.EncodeToString(pis[i])
		}
		out["membership_public_inputs"] = map[string]any{
			"root": hex.EncodeToString(m.Root), "nullifier": hex.EncodeToString(m.Nullifier), "scope": hx(privacy.ClaimScope(20360)),
			"signal": hx(fe(9)), "excluded_dsc": hx(fe(10)), "excluded_country": hx(privacy.CountryField("FR")),
			"max_activation": uint64(personhoodtypes.NoBound), "max_predecessor": 1_750_000_000, "inputs": ps,
		}
	}

	// ---- move public inputs (circuits/move, 8acf58f) -----------------------
	{
		m := moveProof(72)
		pis := personhoodtypes.MovePublicInputs(m, privacy.HandleScope(), fe(9))
		ps := make([]string, len(pis))
		for i := range pis {
			ps[i] = hex.EncodeToString(pis[i])
		}
		out["move_public_inputs"] = map[string]any{
			"root": hex.EncodeToString(m.Root), "old_nullifier": hex.EncodeToString(m.OldNullifier), "new_nullifier": hex.EncodeToString(m.NewNullifier),
			"scope": hx(privacy.HandleScope()), "signal": hx(fe(9)), "inputs": ps,
		}
	}

	// ---- chain wave 3 (06ea4d6) ---------------------------------------------
	// The module accounts an unshield may not pay (B/F2), canonical vote
	// weights (F3).
	modules := map[string]string{}
	for _, n := range []string{"fee_collector", "distribution", "mint", "bonded_tokens_pool", "not_bonded_tokens_pool", "gov", "nft",
		"transfer", "interchainaccounts", "shielded", "shieldedstaking", "dex", "allocation", "personhood", "earth", "wasm"} {
		s, err := ac.BytesToString(authtypes.NewModuleAddress(n))
		must(err)
		modules[n] = s
	}
	out["module_accounts"] = modules
	out["legacy_dec"] = map[string]string{"1": math.LegacyMustNewDecFromStr("1").String(), "0.5": math.LegacyMustNewDecFromStr("0.5").String()}

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	must(enc.Encode(out))
}
