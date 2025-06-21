// src/navigation/ActionStackNavigator.tsx
import React from 'react';
import {createStackNavigator} from '@react-navigation/stack';

// RENAMED THE IMPORT HERE
import NavListScreen from '../screens/NavListScreen';

// Other screen imports remain the same
import ANMLClaimScreen from '../screens/ANMLClaimScreen';
import SwapTokensScreen from '../screens/SwapScreen';
import ManageLPScreen from '../screens/ManageLPScreen';
import StakeERTHScreen from '../screens/StakeERTHScreen';
import CaretakerFundScreen from '../screens/governance/CaretakerFundScreen';
import DeflationFundScreen from '../screens/governance/DeflationFundScreen';


const Stack = createStackNavigator();

const ActionStackNavigator = () => {
  return (
    <Stack.Navigator initialRouteName="ANMLClaim">
      {/* UPDATED THE COMPONENT AND NAME HERE */}
      <Stack.Screen name="NavList" component={NavListScreen} options={{title: 'Actions'}} />

      {/* All destination screens remain the same */}
      <Stack.Screen name="ANMLClaim" component={ANMLClaimScreen} options={{title: 'ANML Claim'}} />
      <Stack.Screen name="SwapTokens" component={SwapTokensScreen} options={{title: 'Swap Tokens'}} />
      <Stack.Screen name="ManageLP" component={ManageLPScreen} options={{title: 'Manage LP'}} />
      <Stack.Screen name="StakeERTH" component={StakeERTHScreen} options={{title: 'Stake ERTH'}} />
      <Stack.Screen name="CaretakerFund" component={CaretakerFundScreen} options={{title: 'Caretaker Fund'}} />
      <Stack.Screen name="DeflationFund" component={DeflationFundScreen} options={{title: 'Deflation Fund'}} />
    </Stack.Navigator>
  );
};

export default ActionStackNavigator;