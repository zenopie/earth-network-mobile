// Command vintagevectors prints the stake vintage (chain v1.3.0) golden
// vectors iOS's VintageTests read (Tests/EarthCoreTests/Resources/vintages/
// vectors.json): the vintage month maths and denoms, every stake msg's
// encoding and sighash with its vintage field (and vintage 0 hashing as
// before), MsgCheckIn's, a retired withdrawal's, and the public inputs the
// chain lays out for each under a VintageCtx; since fix pass 1, the
// month-boundary grace (NewVintageCtx, CheckCredit), credit_vintage on
// MsgRedelegate and MsgCheckIn, and the LCD's JSON for Query/VintageInfo,
// Query/VintageBooks and a GroundworksVote (no split_expires_at). Run it through gen.sh, which
// points a throwaway module at a committed chain ref. The helpers below are
// tools/privacyvectors/main.go's, so a msg here is the one its vectors name.
package main

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"time"

	"cosmossdk.io/math"
	"github.com/consensys/gnark-crypto/ecc/bn254/fr"
	addresscodec "github.com/cosmos/cosmos-sdk/codec/address"
	sdk "github.com/cosmos/cosmos-sdk/types"
	"github.com/cosmos/cosmos-sdk/types/query"
	govv1 "github.com/cosmos/cosmos-sdk/x/gov/types/v1"
	"github.com/cosmos/gogoproto/jsonpb"
	"github.com/cosmos/gogoproto/proto"

	allocationtypes "github.com/earth-network/earth/x/allocation/types"
	shieldedtypes "github.com/earth-network/earth/x/shielded/types"
	stakingtypes "github.com/earth-network/earth/x/shieldedstaking/types"
	"github.com/earth-network/earth/zk/orchard"
	"github.com/earth-network/earth/zk/poseidon2"
	"github.com/earth-network/earth/zk/privacy"
)

const chainID = "earth-1"

func hx(e fr.Element) string { return hex.EncodeToString(privacy.FieldBytes(e)) }
func u(v uint64) fr.Element  { return privacy.U64(v) }
func fe(i uint64) fr.Element { return poseidon2.Hash([]fr.Element{u(i)}) }
func fb(i uint64) []byte     { return privacy.FieldBytes(fe(i)) }

func must(err error) {
	if err != nil {
		panic(err)
	}
}

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

func sct(seed byte) []byte {
	b := make([]byte, privacy.WalletStakeCiphertextBytes)
	for i := range b {
		b[i] = seed ^ byte(i)
	}
	return b
}

func bct(seed byte) []byte {
	b := make([]byte, privacy.BlindNoteCiphertextBytes)
	for i := range b {
		b[i] = seed + byte(i)
	}
	return b
}

func stakeProof(seed uint64, spends int, creates, credits, clears bool) stakingtypes.StakeProof {
	zero := make([]byte, 32)
	p := stakingtypes.StakeProof{Proof: []byte{0x5e, byte(seed)}, Anchor: fb(seed),
		GroundworksTags: [][]byte{fb(seed + 8), fb(seed + 9)},
		Commitment:      zero, CreditNullifier: zero, CreditCommitment: zero, DebtRoot: zero,
		CreditGroundworksTag: zero, VoteTag: zero, CreditVoteTag: zero, PendingKey: zero}
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
		p.CreditGroundworksTag = fb(seed + 10)
	}
	if clears {
		p.ClearBefore, p.DebtRoot = 1_790_000_000+seed, fb(seed+6)
	}
	return p
}

func voting(p stakingtypes.StakeProof, seed, w, cw uint64) stakingtypes.StakeProof {
	p.VoteTag, p.VoteWeight = fb(seed+11), w
	if cw > 0 {
		p.CreditVoteTag, p.CreditVoteWeight = fb(seed+12), cw
	}
	return p
}

type msgVec struct {
	TypeURL  string `json:"type_url"`
	Proto    string `json:"proto"`
	Sighash  string `json:"sighash"`
	GasLimit uint64 `json:"gas_limit"`
}

func main() {
	cfg := sdk.GetConfig()
	cfg.SetBech32PrefixForAccount("earth", "earthpub")
	cfg.SetBech32PrefixForValidator("earthvaloper", "earthvaloperpub")
	ac := addresscodec.NewBech32Codec("earth")
	out := map[string]any{"chain_id": chainID}
	valRaw := make([]byte, 20)
	val, err := addresscodec.NewBech32Codec("earthvaloper").BytesToString(valRaw)
	must(err)
	val2raw := make([]byte, 20)
	for i := range val2raw {
		val2raw[i] = byte(i + 1)
	}
	val2, err := addresscodec.NewBech32Codec("earthvaloper").BytesToString(val2raw)
	must(err)
	out["validator"], out["validator2"] = val, val2

	// ---- months -------------------------------------------------------------
	const base, cur = uint32(202610), uint32(202703)
	vc := stakingtypes.VintageCtx{Base: base, Current: cur}
	out["base"], out["current"] = base, cur
	type monthV struct {
		Month     uint32 `json:"month"`
		Valid     bool   `json:"valid"`
		Start     int64  `json:"start"`
		RetiresAt int64  `json:"retires_at"`
		Live      bool   `json:"live"`
		Retired   bool   `json:"retired"`
		Denom     string `json:"denom"`
		Asset     string `json:"asset"`
	}
	var months []monthV
	for _, m := range []uint32{202509, 202602, 202603, 202604, 202610, 202612, 202701, 202703, 202704, 209912} {
		mv := monthV{Month: m, Valid: stakingtypes.ValidateVintage(m) == nil, Start: stakingtypes.VintageStart(m).Unix(),
			RetiresAt: stakingtypes.VintageRetiresAt(m).Unix(), Live: stakingtypes.VintageLive(m, cur), Retired: stakingtypes.VintageRetired(m, cur),
			Denom: stakingtypes.VintageDenom(val, m, base)}
		mv.Asset = hx(privacy.AssetID(mv.Denom))
		months = append(months, mv)
	}
	out["months"] = months
	var invalid []uint32
	for _, m := range []uint32{0, 199912, 202600, 202613, 1000001, 999913} {
		if stakingtypes.ValidateVintage(m) != nil {
			invalid = append(invalid, m)
		}
	}
	out["invalid_months"] = invalid
	monthOf := map[string]uint32{}
	for _, t := range []int64{0, 1_790_000_000, 1_793_491_199, 1_793_491_200, 1_798_761_599, 1_798_761_600, 4_102_444_800} {
		monthOf[fmt.Sprint(t)] = stakingtypes.MonthOf(time.Unix(t, 0))
	}
	out["month_of"] = monthOf

	// ---- month-boundary grace (fix pass 1) ----------------------------------
	out["grace_seconds"] = stakingtypes.VintageGraceSeconds
	type graceV struct {
		T       int64    `json:"t"`
		Current uint32   `json:"current"`
		Grace   uint32   `json:"grace"`
		Accepts []uint32 `json:"accepts"`
	}
	var graces []graceV
	curStart := stakingtypes.VintageStart(cur).Unix()
	for _, t := range []int64{curStart - 1, curStart, curStart + 600, curStart + 3599, curStart + 3600, curStart + 86_400,
		stakingtypes.VintageStart(202701).Unix() + 10} {
		g := stakingtypes.NewVintageCtx(base, time.Unix(t, 0))
		gv := graceV{T: t, Current: g.Current, Grace: g.Grace, Accepts: []uint32{}}
		for _, m := range []uint32{stakingtypes.AddMonths(g.Current, -2), stakingtypes.AddMonths(g.Current, -1), g.Current, stakingtypes.AddMonths(g.Current, 1)} {
			if g.CheckCredit(m) == nil {
				gv.Accepts = append(gv.Accepts, m)
			}
		}
		graces = append(graces, gv)
	}
	out["grace"] = graces
	type parseV struct {
		Denom   string `json:"denom"`
		OK      bool   `json:"ok"`
		Valoper string `json:"valoper"`
		Month   uint32 `json:"month"`
	}
	var parses []parseV
	for _, d := range []string{"derth/" + val, "derth/" + val + "/202611", "derth/" + val + "/202613", "derth/" + val + "/20261",
		"derth/" + val + "/2026110", "derth/" + val + "/202611/1", "derth/", "derth//202611", "derth/" + val + "/", "uerth", "derth/" + val + "/20261a"} {
		v, m, ok := stakingtypes.ParseDerthDenom(d)
		parses = append(parses, parseV{d, ok, v, m})
	}
	out["parse"] = parses

	// ---- msgs ---------------------------------------------------------------
	msgs := map[string]msgVec{}
	txf := orchard.TxFields{GasLimit: 2_600_000}
	add := func(name string, m sdk.Msg) {
		bz, err := proto.Marshal(m)
		must(err)
		pm := m.(shieldedtypes.PrivateMsg)
		s, err := shieldedtypes.Sighash(pm, chainID, txf, ac)
		must(err)
		msgs[name] = msgVec{TypeURL: sdk.MsgTypeURL(m), Proto: hex.EncodeToString(bz), Sighash: hx(s), GasLimit: txf.GasLimit}
	}
	splits := []allocationtypes.AllocationWeight{{OptionId: 2, Percent: 100}}
	opts := []*govv1.WeightedVoteOption{{Option: govv1.OptionYes, Weight: "0.700000000000000000"}, {Option: govv1.OptionNo, Weight: "0.300000000000000000"}}
	// The same msgs as tools/privacyvectors' delegate/restake/undelegate/redelegate/stake_vote_two, with a vintage.
	for _, vin := range []uint32{0, base, cur} {
		sfx := fmt.Sprintf("_%d", vin)
		add("delegate"+sfx, &stakingtypes.MsgDelegate{Bundle: fee(90, 502000), Validator: val, Amount: 500000, Derth: 449_995, Stake: stakeProof(90, 1, true, false, true), Vintage: vin})
		add("restake"+sfx, &stakingtypes.MsgRestake{Bundle: fee(95, 2000), Validator: val, Stake: stakeProof(95, 2, true, false, true), Vintage: vin})
		add("undelegate"+sfx, &stakingtypes.MsgUndelegate{Bundle: fee(100, 2000), Validator: val, Amount: 400000, Stake: stakeProof(100, 1, true, false, true), Pc: fb(101), Ciphertext: bct(101), Vintage: vin})
		add("redelegate"+sfx, &stakingtypes.MsgRedelegate{Bundle: fee(175, 2000), SrcValidator: val, DstValidator: val2, Amount: 400000,
			Stake: stakeProof(175, 1, true, true, true), DstDerth: 380_000, MoveTime: 1_790_000_123, Vintage: vin})
		add("stake_vote"+sfx, &stakingtypes.MsgStakeVote{Bundle: fee(127, 2000), ProposalId: 6, Validator: val, Options: opts, Weight: 999, Proof: []byte{0x70, 0x7e},
			VoteNullifiers: [][]byte{fb(128), fb(129)}, DebtRoot: fb(130), Vintage: vin})
	}
	// A check-in from the base vintage: lane A spends one note into its change, the credit lane pads into a new note.
	checkIn := &stakingtypes.MsgCheckIn{Bundle: fee(180, 2000), Validator: val, FromVintage: base, Amount: 400000, Stake: stakeProof(180, 1, true, true, true)}
	add("check_in", checkIn)
	checkInVote := &stakingtypes.MsgCheckIn{Bundle: fee(185, 2000), Validator: val, FromVintage: 202612, Amount: 400000,
		Stake: voting(stakeProof(185, 2, true, true, true), 185, 500_000, 1_400_000), GroundworksSplit: splits}
	add("check_in_vote", checkInVote)
	// A retired vintage's withdrawal: the whole note into a zero output, no split, no vote.
	withdraw := &stakingtypes.MsgUndelegate{Bundle: fee(190, 2000), Validator: val, Amount: 400000, Stake: stakeProof(190, 1, true, false, true),
		Pc: fb(191), Ciphertext: bct(191), Vintage: 202602}
	add("withdraw_retired", withdraw)
	// credit_vintage (fix pass 1): the current month explicit, and the
	// previous month in the grace; appended to the sighash when non-zero.
	prev := stakingtypes.AddMonths(cur, -1)
	redCredit := func(cv uint32) *stakingtypes.MsgRedelegate {
		return &stakingtypes.MsgRedelegate{Bundle: fee(175, 2000), SrcValidator: val, DstValidator: val2, Amount: 400000,
			Stake: stakeProof(175, 1, true, true, true), DstDerth: 380_000, MoveTime: 1_790_000_123, Vintage: base, CreditVintage: cv}
	}
	checkCredit := func(cv uint32) *stakingtypes.MsgCheckIn {
		return &stakingtypes.MsgCheckIn{Bundle: fee(180, 2000), Validator: val, FromVintage: base, Amount: 400000,
			Stake: stakeProof(180, 1, true, true, true), CreditVintage: cv}
	}
	add("redelegate_credit", redCredit(cur))
	add("redelegate_credit_grace", redCredit(prev))
	add("check_in_credit", checkCredit(cur))
	add("check_in_credit_grace", checkCredit(prev))
	out["msgs"] = msgs
	// ValidateBasic: a credit_vintage needs an explicit vintage.
	noVin := redCredit(cur)
	noVin.Vintage = 0
	validate := map[string]string{}
	for name, m := range map[string]sdk.HasValidateBasic{"redelegate_credit": redCredit(cur), "redelegate_credit_no_vintage": noVin,
		"check_in_credit": checkCredit(cur), "check_in_credit_bad": checkCredit(202613)} {
		if err := m.ValidateBasic(); err != nil {
			validate[name] = err.Error()
		} else {
			validate[name] = ""
		}
	}
	out["validate"] = validate

	// ---- public inputs under vc --------------------------------------------
	pis := map[string][]string{}
	enc := func(in [][]byte) []string {
		ss := make([]string, len(in))
		for i := range in {
			ss[i] = hex.EncodeToString(in[i])
		}
		return ss
	}
	for name, m := range map[string]stakingtypes.StakeMsg{
		"check_in":         checkIn,
		"check_in_vote":    checkInVote,
		"withdraw_retired": withdraw,
		"delegate_current": &stakingtypes.MsgDelegate{Validator: val, Amount: 500000, Derth: 449_995, Stake: stakeProof(90, 1, true, false, true), Vintage: cur},
		"redelegate_base": &stakingtypes.MsgRedelegate{SrcValidator: val, DstValidator: val2, Amount: 400000,
			Stake: stakeProof(175, 1, true, true, true), DstDerth: 380_000, MoveTime: 1_790_000_123, Vintage: base},
	} {
		pis[name] = enc(m.StakeProofOf().PublicInputs(m.StakeLanes(vc), fe(77)))
	}
	// In the grace (10 minutes into cur): the credit lane proves the previous month's asset.
	gvc := stakingtypes.NewVintageCtx(base, time.Unix(curStart+600, 0))
	for name, m := range map[string]stakingtypes.StakeMsg{
		"redelegate_credit":       redCredit(cur),
		"redelegate_credit_grace": redCredit(prev),
		"check_in_credit":         checkCredit(cur),
		"check_in_credit_grace":   checkCredit(prev),
	} {
		pis[name] = enc(m.StakeProofOf().PublicInputs(m.StakeLanes(gvc), fe(77)))
	}
	for _, vin := range []uint32{base, 202612} {
		vm := &stakingtypes.MsgStakeVote{ProposalId: 6, Validator: val, Weight: 999, VoteNullifiers: [][]byte{fb(128), fb(129)}, DebtRoot: fb(130), Vintage: vin}
		pis[fmt.Sprintf("stake_vote_%d", vin)] = enc(vm.VotePublicInputs(vc, fb(131), fb(132), fe(77)))
	}
	out["public_inputs"] = pis

	// ---- the LCD's JSON (gateway: original names, defaults emitted) ---------
	jm := jsonpb.Marshaler{OrigName: true, EmitDefaults: true}
	js := func(m proto.Message) json.RawMessage {
		var buf bytes.Buffer
		must(jm.Marshal(&buf, m))
		return json.RawMessage(buf.Bytes())
	}
	nextRet := stakingtypes.VintageStart(stakingtypes.AddMonths(cur, 1)).Unix()
	out["vintage_info"] = js(&stakingtypes.QueryVintageInfoResponse{CurrentVintage: cur, BaseVintage: base, NextRetirement: nextRet})
	i := func(v int64) *math.Int { x := math.NewInt(v); return &x }
	zero := math.ZeroInt()
	rec := func(st stakingtypes.UnbondStatus, done int64) *stakingtypes.UnbondRecord {
		return &stakingtypes.UnbondRecord{Validator: val2, Epoch: 400, Status: st, Requested: math.NewInt(5_000_000), Target: math.NewInt(5_000_000),
			Undelegated: math.NewInt(5_000_000), CompletionTime: done * int64(time.Second), Payout: zero, Outstanding: math.NewInt(5_000_000), Paid: zero}
	}
	retiredAt := stakingtypes.VintageRetiresAt(202601).Unix() + 3000 // booked at the epoch end after its boundary
	books := &stakingtypes.QueryVintageBooksResponse{CurrentVintage: cur, BaseVintage: base, NextRetirement: nextRet,
		Pagination: &query.PageResponse{NextKey: []byte{0x01, 0x02, 0xfe}},
		Entries: []stakingtypes.VintageBookEntry{
			{Validator: val, Vintage: base, Denom: stakingtypes.VintageDenom(val, base, base), State: "live", Supply: i(7_000_000),
				RetiresAt: stakingtypes.VintageRetiresAt(base).Unix()},
			{Validator: val, Vintage: cur, Denom: stakingtypes.VintageDenom(val, cur, base), State: "live", Supply: i(1_000_000),
				RetiresAt: stakingtypes.VintageRetiresAt(cur).Unix()},
			{Validator: val, Vintage: 202602, Denom: stakingtypes.VintageDenom(val, 202602, base), State: "retiring", Supply: i(3_000_000),
				RetiresAt: stakingtypes.VintageRetiresAt(202602).Unix(), ExpectedCompletion: curStart + 86_400 + 21*86_400},
			{Validator: val2, Vintage: 202601, Denom: stakingtypes.VintageDenom(val2, 202601, base), State: "retired",
				RetiresAt: stakingtypes.VintageRetiresAt(202601).Unix(),
				Retired: &stakingtypes.RetiredVintage{Validator: val2, Vintage: 202601, Supply: math.NewInt(4_500_000), Value: math.NewInt(5_000_000),
					PayoutId: 9, Epoch: 400, RemainingSupply: math.NewInt(4_500_000), RemainingValue: math.NewInt(5_000_000), RetiredAt: retiredAt},
				Record: rec(stakingtypes.UNBOND_STATUS_UNBONDING, retiredAt+21*86_400), ExpectedCompletion: retiredAt + 21*86_400},
			{Validator: val2, Vintage: 202512, Denom: stakingtypes.VintageDenom(val2, 202512, base), State: "retired",
				RetiresAt: stakingtypes.VintageRetiresAt(202512).Unix(),
				Retired: &stakingtypes.RetiredVintage{Validator: val2, Vintage: 202512, Supply: math.NewInt(2_000_000), Value: math.NewInt(2_200_000),
					PayoutId: 8, Epoch: 370, RemainingSupply: math.NewInt(1_000_000), RemainingValue: math.NewInt(1_100_000), RetiredAt: stakingtypes.VintageRetiresAt(202512).Unix() + 3000},
				Record: rec(stakingtypes.UNBOND_STATUS_MATURED, stakingtypes.VintageRetiresAt(202512).Unix()+3000+21*86_400)},
		}}
	out["vintage_books"] = js(books)
	out["groundworks_vote"] = js(&stakingtypes.GroundworksVote{Id: 12, Validator: val, Derth: math.NewInt(1_500_000), Weight: math.NewInt(1_650_000),
		Splits: []allocationtypes.AllocationWeight{{OptionId: 2, Percent: 100}}, CreatedHeight: 77, SplitEpoch: 3, Tag: fb(140),
		Pending: math.ZeroInt(), Vintage: cur})

	enc2 := json.NewEncoder(os.Stdout)
	enc2.SetIndent("", " ")
	must(enc2.Encode(out))
}
