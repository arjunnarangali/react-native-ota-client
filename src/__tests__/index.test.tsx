import { expect, it, jest } from '@jest/globals';

jest.mock('react-native', () => ({
  TurboModuleRegistry: {
    getEnforcing: jest.fn(() => ({
      getStatus: jest.fn(),
      checkForUpdate: jest.fn(),
      markUpdateSuccessful: jest.fn(),
      closeAppToApplyUpdate: jest.fn(),
    })),
  },
}));

import { OTAUpdates } from '..';
import { TurboModuleRegistry } from 'react-native';

it('exports the OTA TurboModule from the public package entry point', () => {
  expect(TurboModuleRegistry.getEnforcing).toHaveBeenCalledWith('OTAUpdates');
  expect(OTAUpdates.getStatus).toEqual(expect.any(Function));
});
