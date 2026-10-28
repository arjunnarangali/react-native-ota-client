import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type OtaUpdateStatus = {
  enabled: boolean;
  status: string;
  message: string;
  runtimeVersion: string;
  channel: string;
  currentVersion: number;
  pendingVersion: number | null;
  pendingRestart: boolean;
  updateAvailable: boolean;
  action: string | null;
};

export interface Spec extends TurboModule {
  getStatus(): Promise<OtaUpdateStatus>;
  checkForUpdate(): Promise<OtaUpdateStatus>;
  markUpdateSuccessful(): Promise<OtaUpdateStatus>;
  closeAppToApplyUpdate(): Promise<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('OTAUpdates');
