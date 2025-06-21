// src/screens/NavListScreen.tsx
import React, {useState} from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  ScrollView,
  Linking,
} from 'react-native';
import Icon from 'react-native-vector-icons/Ionicons';

// RENAMED THE COMPONENT HERE
const NavListScreen = ({navigation}) => {
  const [isGovernanceOpen, setIsGovernanceOpen] = useState(false);

  const mainOptions = [
    {title: 'ANML Claim', screen: 'ANMLClaim'},
    {title: 'Swap Tokens', screen: 'SwapTokens'},
    {title: 'Manage LP', screen: 'ManageLP'},
    {title: 'Stake ERTH', screen: 'StakeERTH'},
    {
      title: 'SCRT Dashboard',
      action: () => Linking.openURL('https://dash.scrt.network/'),
    },
  ];

  const governanceSubOptions = [
    {title: 'Caretaker Fund', screen: 'CaretakerFund'},
    {title: 'Deflation Fund', screen: 'DeflationFund'},
  ];

  return (
    <ScrollView style={styles.container}>
      {mainOptions.map((option, index) => (
        <TouchableOpacity
          key={index}
          style={styles.option}
          onPress={() => {
            if (option.screen) navigation.navigate(option.screen);
            else if (option.action) option.action();
          }}>
          <Text style={styles.optionText}>{option.title}</Text>
          <Text style={styles.arrow}>›</Text>
        </TouchableOpacity>
      ))}

      {/* --- Governance Collapsible Section --- */}
      <View>
        <TouchableOpacity
          style={styles.option}
          onPress={() => setIsGovernanceOpen(!isGovernanceOpen)}>
          <Text style={styles.optionText}>Governance</Text>
          <Icon
            name={isGovernanceOpen ? 'chevron-down-outline' : 'chevron-forward-outline'}
            size={22}
            color="#ccc"
          />
        </TouchableOpacity>
        {isGovernanceOpen && (
          <View style={styles.subOptionContainer}>
            {governanceSubOptions.map((subOption, subIndex) => (
              <TouchableOpacity
                key={subIndex}
                style={styles.subOption}
                onPress={() => navigation.navigate(subOption.screen)}>
                <Text style={styles.subOptionText}>{subOption.title}</Text>
                <Text style={styles.arrow}>›</Text>
              </TouchableOpacity>
            ))}
          </View>
        )}
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f0f0f0' },
  option: { backgroundColor: 'white', paddingHorizontal: 20, paddingVertical: 15, borderBottomWidth: 1, borderBottomColor: '#e0e0e0', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  optionText: { fontSize: 18 },
  arrow: { fontSize: 24, color: '#ccc' },
  subOptionContainer: { paddingLeft: 20, backgroundColor: '#fafafa' },
  subOption: { backgroundColor: 'transparent', paddingHorizontal: 20, paddingVertical: 15, borderBottomWidth: 1, borderBottomColor: '#e8e8e8', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  subOptionText: { fontSize: 16 },
});

// RENAMED THE EXPORT HERE
export default NavListScreen;