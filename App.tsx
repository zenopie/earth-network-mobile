// App.tsx
import React from 'react';
import {NavigationContainer} from '@react-navigation/native';
import {createBottomTabNavigator} from '@react-navigation/bottom-tabs';
import Icon from 'react-native-vector-icons/Ionicons';

// Import our two main navigation components
import WalletScreen from './src/screens/WalletScreen';
import ActionStackNavigator from './src/navigation/ActionStackNavigator';

const Tab = createBottomTabNavigator();

const App = () => {
  return (
    <NavigationContainer>
      <Tab.Navigator
        initialRouteName="Actions"
        screenOptions={({ route }) => ({
          headerShown: false, // Hides the header on top of the tabs
          tabBarIcon: ({ focused, color, size }) => {
            let iconName;

            if (route.name === 'Wallet') {
              iconName = focused ? 'wallet' : 'wallet-outline';
            } else if (route.name === 'Actions') {
              iconName = focused ? 'apps' : 'apps-outline';
            }

            // This component renders the icon for the tab
            return <Icon name={iconName} size={size} color={color} />;
          },
          tabBarActiveTintColor: '#003366', // Color for the active tab
          tabBarInactiveTintColor: 'gray',   // Color for inactive tabs
        })}
      >
        <Tab.Screen name="Wallet" component={WalletScreen} />
        <Tab.Screen name="Actions" component={ActionStackNavigator} />
      </Tab.Navigator>
    </NavigationContainer>
  );
};

export default App;