// src/utils/tokens.ts

// Define the structure of a token for TypeScript
export interface TokenDetails {
    contract: string;
    hash: string;
    decimals: number;
    logo: any; // Use 'any' for require() statements
    coingeckoId?: string;
    lp?: {
      contract: string;
      hash: string;
      decimals: number;
    };
  }
  
  export const tokens: Record<string, TokenDetails> = {
    ERTH: {
      contract: "secret16snu3lt8k9u0xr54j2hqyhvwnx9my7kq7ay8lp",
      hash: "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
      decimals: 6,
      logo: require('../../images/coin/ERTH.png'), // <-- Mobile-friendly path
    },
    ANML: {
      contract: "secret14p6dhjznntlzw0yysl7p6z069nk0skv5e9qjut",
      hash: "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
      decimals: 6,
      logo: require('../../images/coin/ANML.png'), // <-- Mobile-friendly path
      lp: {
        contract: "secret1cqxxq586zl6g5zly3536rc7crwfcmeluuwehvx",
        hash: "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
        decimals: 6,
      },
    },
    sSCRT: {
      contract: "secret1k0jntykt7e4g3y88ltc60czgjuqdy4c9e8fzek",
      hash: "af74387e276be8874f07bec3a87023ee49b0e7ebe08178c49d0a49c3c98ed60e",
      decimals: 6,
      logo: require('../../images/coin/SSCRT.png'), // <-- Mobile-friendly path
      coingeckoId: "secret",
      lp: {
        contract: "secret1lqmvkxrhqfjj64ge8cr9v8pwnz4enhw7f6hdys",
        hash: "638a3e1d50175fbcb8373cf801565283e3eb23d88a9b7b7f99fcc5eb1e6b561e",
        decimals: 6,
      },
    },
  };