import { useEffect, useState } from 'react';
import { Button, Text, View, StyleSheet } from 'react-native';
import { OTAUpdates, type OtaUpdateStatus } from 'react-native-ota-client';

export default function App() {
  const [status, setStatus] = useState<OtaUpdateStatus | null>(null);
  const [message, setMessage] = useState('');

  useEffect(() => {
    OTAUpdates.markUpdateSuccessful()
      .then(setStatus)
      .catch((error: unknown) => {
        setMessage(error instanceof Error ? error.message : String(error));
      });
  }, []);

  const checkForUpdate = async () => {
    try {
      const result = await OTAUpdates.checkForUpdate();
      setStatus(result);
      setMessage(result.message);
      if (result.pendingRestart) {
        setMessage(
          `${result.message} On iOS, leave and reopen the app to apply it.`
        );
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    }
  };

  return (
    <View style={styles.container}>
      <Text>OTA: {status?.status ?? 'loading'}</Text>
      <Text>{message || status?.message}</Text>
      <Button title="Check for updates" onPress={checkForUpdate} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
