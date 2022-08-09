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

    respond: (requestId, code, type, body, headers) => {
        Server.respond(requestId, code, type, body, headers);
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