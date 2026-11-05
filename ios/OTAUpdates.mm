#import <OtaSdkSpec/OtaSdkSpec.h>
#import "OtaSdk-Swift.h"

@interface OTAUpdates : NSObject <NativeOTAUpdatesSpec>
@end

@implementation OTAUpdates

RCT_EXPORT_MODULE(OTAUpdates)

RCT_EXPORT_METHOD(getStatus:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  resolve([OtaSdkUpdates status]);
}

RCT_EXPORT_METHOD(checkForUpdate:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  [OtaSdkUpdates checkForUpdateWithCompletion:^(NSDictionary * _Nullable status, NSError * _Nullable error) {
    if (error != nil) {
      NSMutableDictionary *failure = [[OtaSdkUpdates status] mutableCopy];
      failure[@"status"] = @"error";
      failure[@"message"] = error.localizedDescription;
      failure[@"updateAvailable"] = @NO;
      resolve(failure);
    } else {
      resolve(status ?: [OtaSdkUpdates status]);
    }
  }];
}

RCT_EXPORT_METHOD(markUpdateSuccessful:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  [OtaSdkUpdates markUpdateSuccessfulWithCompletion:^(NSDictionary * _Nullable status, NSError * _Nullable error) {
    if (error != nil) reject(@"OTA_CONFIRM_FAILED", error.localizedDescription, error);
    else resolve(status ?: [OtaSdkUpdates status]);
  }];
}

RCT_EXPORT_METHOD(closeAppToApplyUpdate:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  // iOS cannot terminate itself. Resolve whether a verified release is waiting for a user relaunch.
  resolve(@([OtaSdkUpdates hasPendingUpdate]));
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeOTAUpdatesSpecJSI>(params);
}

@end
