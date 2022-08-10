/**
 * @providesModule react-native-http-server
 */
'use strict';

import {DeviceEventEmitter} from 'react-native';
import {NativeModules} from 'react-native';
var Server = NativeModules.HttpServer;
const httpServer = {
    start: (port, serviceName, callback) => {
        if (port == 80) {
            throw "Invalid server port specified. Port 80 is reserved.";
        }

        Server.start(port, serviceName);
        DeviceEventEmitter.addListener('httpServerResponseReceived', callback);
    },

    stop: () => {
        Server.stop();
        DeviceEventEmitter.removeAllListeners('httpServerResponseReceived');
    },

    respond: (requestId, code, type, body, headers=null) => {
        Server.respond(requestId, code, type, body, headers);
    },

    respondWithFile: (requestId, filePath, range=null, maxAge=3600, headers=null) => {
        var byteRange = {from:null, to:null}
        if (range && (typeof range === 'string')) {
            var match = /(\d*)-(\d*)/.exec(range);
            if (match) {
                if (match[1]) byteRange.from = parseInt(match[1]);
                if (match[2]) byteRange.to = parseInt(match[2]);
            }
        }
        Server.respondWithFile(requestId, filePath, byteRange, maxAge, headers);
    },

    setRootDoc: (path) => {
        Server.setRootDoc(path);
    },

    isRunning: async () => {
        try {
            return await Server.isRunning();
        } catch (e) {
            return false;
        }
    }
}

export default httpServer;