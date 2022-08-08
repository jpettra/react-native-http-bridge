#import "RCTHttpServer.h"
#import "React/RCTBridge.h"
#import "React/RCTLog.h"
#import "React/RCTEventDispatcher.h"

#import "GCDWebServer.h"
#import "GCDWebServerDataResponse.h"
#import "GCDWebServerDataRequest.h"
#import "GCDWebServerPrivate.h"
#include <stdlib.h>

@interface RCTHttpServer : NSObject <RCTBridgeModule> {
    GCDWebServer* _webServer;
    NSMutableDictionary* _completionBlocks;
    NSString* _rootPath;
}
@end

static RCTBridge *bridge;

@implementation RCTHttpServer

@synthesize bridge = _bridge;

RCT_EXPORT_MODULE();


- (void)initResponseReceivedFor:(GCDWebServer *)server forType:(NSString*)type {
    [server addDefaultHandlerForMethod:type
                          requestClass:[GCDWebServerDataRequest class]
                     asyncProcessBlock:^(GCDWebServerRequest* request, GCDWebServerCompletionBlock completionBlock) {
        
        long long milliseconds = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
        int r = arc4random_uniform(1000000);
        NSString *requestId = [NSString stringWithFormat:@"%lld:%d", milliseconds, r];
        NSString *filePath = self->_rootPath != nil ? filePath = [self->_rootPath stringByAppendingPathComponent:request.URL.relativeString] : nil;

        
        if (filePath != nil && [[NSFileManager defaultManager] isReadableFileAtPath:filePath]) {
            @try {
                GCDWebServerResponse* requestResponse = nil;
                if ([request hasByteRange]) {
                    requestResponse = [GCDWebServerFileResponse responseWithFile:filePath byteRange:request.byteRange];
                    [requestResponse setValue:@"bytes" forAdditionalHeader:@"Accept-Ranges"];
                } else {
                    requestResponse = [GCDWebServerFileResponse responseWithFile:filePath];
                }
                requestResponse.cacheControlMaxAge = 3600;
                completionBlock(requestResponse);
            } @catch (NSException *exception) {
                NSLog(@"RCTHttpServer response id: %@, error: %@", requestId, exception);
            }
        } else {
            @synchronized (self) {
                [self->_completionBlocks setObject:completionBlock forKey:requestId];
            }

            dispatch_time_t popTime = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(30.0 * NSEC_PER_SEC));
            dispatch_after(popTime, dispatch_get_main_queue(), ^(void){
                // NSLog(@"RCTHttpServer attempt to clean request id: %@", requestId);
                [self getCompletionBlock:requestId];
            });

            // NSLog(@"RCTHttpServer got request id: %@", requestId);
            @try {
                if ([GCDWebServerTruncateHeaderValue(request.contentType) isEqualToString:@"application/json"]) {
                    GCDWebServerDataRequest* dataRequest = (GCDWebServerDataRequest*)request;
                    [self.bridge.eventDispatcher sendAppEventWithName:@"httpServerResponseReceived"
                        body:@{@"requestId": requestId,
                            @"postData": dataRequest.jsonObject,
                            @"type": type,
                            @"headers": request.headers,
                            @"url": request.URL.relativeString}];
                } else {
                    [self.bridge.eventDispatcher sendAppEventWithName:@"httpServerResponseReceived"
                        body:@{@"requestId": requestId,
                            @"type": type,
                            @"headers": request.headers,
                            @"url": request.URL.relativeString}];
                }
            } @catch (NSException *exception) {
                [self.bridge.eventDispatcher sendAppEventWithName:@"httpServerResponseReceived"
                    body:@{@"requestId": requestId,
                        @"type": type,
                        @"url": request.URL.relativeString}];
            }
        }
    }];
}

-(GCDWebServerCompletionBlock)getCompletionBlock: (NSString *) requestId{
    GCDWebServerCompletionBlock completionBlock = nil;
    @synchronized (self) {
        completionBlock = [self->_completionBlocks objectForKey:requestId];
        [self->_completionBlocks removeObjectForKey:requestId];
    }
    // NSLog(@"RCTHttpServer getCompletionBlock request id: %@ ,block: %@", requestId, completionBlock);
    return completionBlock;
}

RCT_EXPORT_METHOD(start:(NSInteger) port
                  serviceName:(NSString *) serviceName)
{
    RCTLogInfo(@"Running HTTP bridge server: %ld", port);
    // NSMutableDictionary *_requestResponses = [[NSMutableDictionary alloc] init];
    self->_completionBlocks = [[NSMutableDictionary alloc] init];
    
    dispatch_sync(dispatch_get_main_queue(), ^{
        _webServer = [[GCDWebServer alloc] init];

        [self initResponseReceivedFor:_webServer forType:@"POST"];
        [self initResponseReceivedFor:_webServer forType:@"PUT"];
        [self initResponseReceivedFor:_webServer forType:@"GET"];
        [self initResponseReceivedFor:_webServer forType:@"DELETE"];
        
        [_webServer startWithPort:port bonjourName:serviceName];
    });
}

RCT_EXPORT_METHOD(stop)
{
    RCTLogInfo(@"Stopping HTTP bridge server");
    
    if (_webServer != nil) {
        [_webServer stop];
        [_webServer removeAllHandlers];
        _webServer = nil;
    }
}

RCT_EXPORT_METHOD(setRootDoc:(NSString *) rootDoc)
{
    _rootPath = rootDoc;
}

RCT_EXPORT_METHOD(isRunning:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)
{
    RCTLogInfo(@"isRunning HTTP bridge server");
    
    if (_webServer != nil) {
        resolve([NSNumber numberWithBool:[_webServer isRunning]]);
    } else {
        reject(@"Error: server is not exists", @"no value return", nil);
    }
}


RCT_EXPORT_METHOD(respond: (NSString *) requestId
                  code: (NSInteger) code
                  type: (NSString *) type
                  body: (NSString *) body
                  headers: (NSDictionary *) headers)
{
    NSData* data = [body dataUsingEncoding:NSUTF8StringEncoding];
    GCDWebServerDataResponse* requestResponse = [[GCDWebServerDataResponse alloc] initWithData:data contentType:type];
    requestResponse.statusCode = code;
    if (headers != NULL && [headers count]){
        for(NSString* key in [headers allKeys]) {
            [requestResponse setValue:(NSString *)[headers objectForKey:key] forAdditionalHeader:key];
        }
    }

    GCDWebServerCompletionBlock completionBlock = [self getCompletionBlock:requestId];

    @try {
        if (completionBlock) completionBlock(requestResponse);
        else NSLog(@"RCTHttpServer response id: %@, missing completionBlock!", requestId);
    } @catch (NSException *exception) {
        NSLog(@"RCTHttpServer response id: %@, error: %@", requestId, exception);
    }
    
}                 

-(void)invalidate
{
    [self stop];
}

@end
