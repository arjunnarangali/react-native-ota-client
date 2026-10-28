import { expect, it, jest } from '@jest/globals';

jest.mock('react-native', () => ({
  TurboModuleRegistry: {
    getEnforcing: jest.fn(() => ({
      multiply: jest.fn((a: number, b: number) => a * b),
      getStatus: jest.fn(),
      checkForUpdate: jest.fn(),
      markUpdateSuccessful: jest.fn(),
      closeAppToApplyUpdate: jest.fn(),
    })),
  },
}));

import { OTAUpdates, multiply } from '..';
import { TurboModuleRegistry } from 'react-native';

it('exports the OTA TurboModule from the public package entry point', () => {
  expect(TurboModuleRegistry.getEnforcing).toHaveBeenCalledWith('OTAUpdates');
  expect(OTAUpdates.getStatus).toEqual(expect.any(Function));
});

it('keeps the template multiply export available', () => {
  expect(multiply(3, 7)).toBe(21);
});
