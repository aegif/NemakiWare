var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __commonJS = (cb, mod) => function __require() {
  return mod || (0, cb[__getOwnPropNames(cb)[0]])((mod = { exports: {} }).exports, mod), mod.exports;
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));

// ../functions/headers.js
var require_headers = __commonJS({
  "../functions/headers.js"(exports, module) {
    "use strict";
    var __defProp2 = Object.defineProperty;
    var __getOwnPropDesc2 = Object.getOwnPropertyDescriptor;
    var __getOwnPropNames2 = Object.getOwnPropertyNames;
    var __hasOwnProp2 = Object.prototype.hasOwnProperty;
    var __export = (target, all) => {
      for (var name in all)
        __defProp2(target, name, { get: all[name], enumerable: true });
    };
    var __copyProps2 = (to, from, except, desc) => {
      if (from && typeof from === "object" || typeof from === "function") {
        for (let key of __getOwnPropNames2(from))
          if (!__hasOwnProp2.call(to, key) && key !== except)
            __defProp2(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc2(from, key)) || desc.enumerable });
      }
      return to;
    };
    var __toCommonJS = (mod) => __copyProps2(__defProp2({}, "__esModule", { value: true }), mod);
    var headers_exports = {};
    __export(headers_exports, {
      CITY_HEADER_NAME: () => CITY_HEADER_NAME2,
      COUNTRY_HEADER_NAME: () => COUNTRY_HEADER_NAME2,
      EMOJI_FLAG_UNICODE_STARTING_POSITION: () => EMOJI_FLAG_UNICODE_STARTING_POSITION2,
      IP_HEADER_NAME: () => IP_HEADER_NAME2,
      LATITUDE_HEADER_NAME: () => LATITUDE_HEADER_NAME2,
      LONGITUDE_HEADER_NAME: () => LONGITUDE_HEADER_NAME2,
      POSTAL_CODE_HEADER_NAME: () => POSTAL_CODE_HEADER_NAME2,
      REGION_HEADER_NAME: () => REGION_HEADER_NAME2,
      REQUEST_ID_HEADER_NAME: () => REQUEST_ID_HEADER_NAME2,
      geolocation: () => geolocation2,
      ipAddress: () => ipAddress2
    });
    module.exports = __toCommonJS(headers_exports);
    var CITY_HEADER_NAME2 = "x-vercel-ip-city";
    var COUNTRY_HEADER_NAME2 = "x-vercel-ip-country";
    var IP_HEADER_NAME2 = "x-real-ip";
    var LATITUDE_HEADER_NAME2 = "x-vercel-ip-latitude";
    var LONGITUDE_HEADER_NAME2 = "x-vercel-ip-longitude";
    var REGION_HEADER_NAME2 = "x-vercel-ip-country-region";
    var POSTAL_CODE_HEADER_NAME2 = "x-vercel-ip-postal-code";
    var REQUEST_ID_HEADER_NAME2 = "x-vercel-id";
    var EMOJI_FLAG_UNICODE_STARTING_POSITION2 = 127397;
    function getHeader(headers, key) {
      return headers.get(key) ?? void 0;
    }
    function getHeaderWithDecode(request, key) {
      const header = getHeader(request.headers, key);
      return header ? decodeURIComponent(header) : void 0;
    }
    function getFlag(countryCode) {
      const regex = new RegExp("^[A-Z]{2}$").test(countryCode);
      if (!countryCode || !regex)
        return void 0;
      return String.fromCodePoint(
        ...countryCode.split("").map((char) => EMOJI_FLAG_UNICODE_STARTING_POSITION2 + char.charCodeAt(0))
      );
    }
    function ipAddress2(input) {
      const headers = "headers" in input ? input.headers : input;
      return getHeader(headers, IP_HEADER_NAME2);
    }
    function getRegionFromRequestId(requestId) {
      if (!requestId) {
        return "dev1";
      }
      return requestId.split(":")[0];
    }
    function geolocation2(request) {
      return {
        // city name may be encoded to support multi-byte characters
        city: getHeaderWithDecode(request, CITY_HEADER_NAME2),
        country: getHeader(request.headers, COUNTRY_HEADER_NAME2),
        flag: getFlag(getHeader(request.headers, COUNTRY_HEADER_NAME2)),
        countryRegion: getHeader(request.headers, REGION_HEADER_NAME2),
        region: getRegionFromRequestId(
          getHeader(request.headers, REQUEST_ID_HEADER_NAME2)
        ),
        latitude: getHeader(request.headers, LATITUDE_HEADER_NAME2),
        longitude: getHeader(request.headers, LONGITUDE_HEADER_NAME2),
        postalCode: getHeader(request.headers, POSTAL_CODE_HEADER_NAME2)
      };
    }
  }
});

// ../functions/middleware.js
var require_middleware = __commonJS({
  "../functions/middleware.js"(exports, module) {
    "use strict";
    var __defProp2 = Object.defineProperty;
    var __getOwnPropDesc2 = Object.getOwnPropertyDescriptor;
    var __getOwnPropNames2 = Object.getOwnPropertyNames;
    var __hasOwnProp2 = Object.prototype.hasOwnProperty;
    var __export = (target, all) => {
      for (var name in all)
        __defProp2(target, name, { get: all[name], enumerable: true });
    };
    var __copyProps2 = (to, from, except, desc) => {
      if (from && typeof from === "object" || typeof from === "function") {
        for (let key of __getOwnPropNames2(from))
          if (!__hasOwnProp2.call(to, key) && key !== except)
            __defProp2(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc2(from, key)) || desc.enumerable });
      }
      return to;
    };
    var __toCommonJS = (mod) => __copyProps2(__defProp2({}, "__esModule", { value: true }), mod);
    var middleware_exports = {};
    __export(middleware_exports, {
      next: () => next2,
      rewrite: () => rewrite2
    });
    module.exports = __toCommonJS(middleware_exports);
    function handleMiddlewareField(init, headers) {
      if (init?.request?.headers) {
        if (!(init.request.headers instanceof Headers)) {
          throw new Error("request.headers must be an instance of Headers");
        }
        const keys = [];
        for (const [key, value] of init.request.headers) {
          headers.set("x-middleware-request-" + key, value);
          keys.push(key);
        }
        headers.set("x-middleware-override-headers", keys.join(","));
      }
    }
    function rewrite2(destination, init) {
      const headers = new Headers(init?.headers ?? {});
      headers.set("x-middleware-rewrite", String(destination));
      handleMiddlewareField(init, headers);
      return new Response(null, {
        ...init,
        headers
      });
    }
    function next2(init) {
      const headers = new Headers(init?.headers ?? {});
      headers.set("x-middleware-next", "1");
      handleMiddlewareField(init, headers);
      return new Response(null, {
        ...init,
        headers
      });
    }
  }
});

// src/index.ts
var import_headers = __toESM(require_headers());
var import_middleware = __toESM(require_middleware());
var export_CITY_HEADER_NAME = import_headers.CITY_HEADER_NAME;
var export_COUNTRY_HEADER_NAME = import_headers.COUNTRY_HEADER_NAME;
var export_EMOJI_FLAG_UNICODE_STARTING_POSITION = import_headers.EMOJI_FLAG_UNICODE_STARTING_POSITION;
var export_IP_HEADER_NAME = import_headers.IP_HEADER_NAME;
var export_LATITUDE_HEADER_NAME = import_headers.LATITUDE_HEADER_NAME;
var export_LONGITUDE_HEADER_NAME = import_headers.LONGITUDE_HEADER_NAME;
var export_POSTAL_CODE_HEADER_NAME = import_headers.POSTAL_CODE_HEADER_NAME;
var export_REGION_HEADER_NAME = import_headers.REGION_HEADER_NAME;
var export_REQUEST_ID_HEADER_NAME = import_headers.REQUEST_ID_HEADER_NAME;
var export_geolocation = import_headers.geolocation;
var export_ipAddress = import_headers.ipAddress;
var export_next = import_middleware.next;
var export_rewrite = import_middleware.rewrite;
export {
  export_CITY_HEADER_NAME as CITY_HEADER_NAME,
  export_COUNTRY_HEADER_NAME as COUNTRY_HEADER_NAME,
  export_EMOJI_FLAG_UNICODE_STARTING_POSITION as EMOJI_FLAG_UNICODE_STARTING_POSITION,
  export_IP_HEADER_NAME as IP_HEADER_NAME,
  export_LATITUDE_HEADER_NAME as LATITUDE_HEADER_NAME,
  export_LONGITUDE_HEADER_NAME as LONGITUDE_HEADER_NAME,
  export_POSTAL_CODE_HEADER_NAME as POSTAL_CODE_HEADER_NAME,
  export_REGION_HEADER_NAME as REGION_HEADER_NAME,
  export_REQUEST_ID_HEADER_NAME as REQUEST_ID_HEADER_NAME,
  export_geolocation as geolocation,
  export_ipAddress as ipAddress,
  export_next as next,
  export_rewrite as rewrite
};
