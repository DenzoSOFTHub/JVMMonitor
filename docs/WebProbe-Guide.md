# Web Probe Guide

## Overview

The Web Probe captures browser user actions without modifying frontend code. A JavaScript beacon is auto-injected into HTML responses by Servlet Filter instrumentation. It works with any web framework: Angular, React, Vue, plain HTML, JSP, Thymeleaf.

When enabled, every HTML response served by the application gets a small script tag appended before the closing `</body>` tag. This script listens for user interactions in the browser and sends them back to the agent as lightweight beacon POST requests.

## What It Captures

### Page Load
DNS resolution time, TCP connection time, time to first byte (TTFB), DOM ready time, full load time, and page title. Uses the Navigation Timing API (Level 1), available in all modern browsers.

### Clicks
Element tag name, id attribute, CSS class, visible text content, and href (for links). Captures clicks on any element in the page.

### AJAX / XMLHttpRequest
HTTP method, URL, response status code, and request duration in milliseconds. Tracks all XMLHttpRequest calls made by the application.

### Fetch API
HTTP method, URL, response status, duration, and errors. Tracks all calls made via the Fetch API.

### JavaScript Errors
Error message, source file, line number, and column number. Also captures unhandled promise rejections.

### SPA Navigation
Route changes detected via URL polling. Works with Angular Router, React Router, Vue Router, and any other client-side routing library that changes the URL.

### Form Submits
Form action URL, HTTP method, and form id attribute. Captured before the POST happens.

## How It Works

1. The Java agent instruments `javax.servlet.http.HttpServlet.service()` to intercept HTTP responses.
2. For HTML responses (Content-Type contains `text/html`), the agent injects the beacon JavaScript before `</body>`.
3. The beacon script listens for user events (clicks, errors, fetch, XHR, navigation, form submit, page load).
4. Events are collected and sent as JSON via async POST requests to `/jvmmonitor-beacon` on the same origin.
5. Requests to `/jvmmonitor-beacon` are intercepted by the agent -- they return a 204 No Content response immediately.
6. The agent parses the beacon JSON and encodes each event as `MSG_INSTR_EVENT` with type `USER_ACTION` (20).
7. Events are sent to the collector over the standard TCP protocol.
8. Events appear in the Instrumentation > All Events table with type "USER".

## Activation

### GUI

Enable from one of:
- **Settings** tab > GUI & Instrumentation
- **Tools** > Agent Modules > enable "webprobe"

### CLI

```
jvm-monitor> enable webprobe 1
```

### Important Notes

- Only available with the Java agent (not the native C agent).
- Disabled by default -- zero overhead when not active.
- When enabled, the agent adds approximately 1 KB to each HTML response (the injected script).

## Deployment Scenarios

### Scenario 1: Spring Boot Serving Angular/React SPA

A typical setup where Angular or React build output is placed in `src/main/resources/static/` and served by Spring Boot.

- The Servlet filter intercepts the `index.html` response and injects the beacon script.
- All SPA routes handled client-side are monitored (route changes, AJAX calls, fetch requests).
- Backend API calls are correlated via the HTTP probe.
- Enable: `enable webprobe 1` -- nothing else needed.

### Scenario 2: Traditional JSP/Thymeleaf Application

Server-rendered HTML pages where each navigation is a full page load.

- Every HTML page served by the Servlet gets the beacon script injected.
- Every page navigation generates a page load event.
- Form submits are captured before the POST happens.
- Enable: `enable webprobe 1`

### Scenario 3: Reverse Proxy (nginx) in Front of Spring Boot

nginx serves static files directly while Spring Boot handles API and some HTML pages.

- The beacon script is injected only in HTML responses that pass through Spring Boot.
- AJAX calls from the browser to Spring Boot API endpoints are captured.
- Static page loads served directly by nginx are NOT captured (the agent never sees those requests).
- Workaround: add the script tag manually in `index.html`:
  `<script>` + content of BeaconScript.SCRIPT + `</script>`

### Scenario 4: Separate Frontend Server (Angular Dev, Port 4200)

During development, the Angular CLI dev server runs on a different port than the backend.

- The Java agent cannot inject the script because the HTML is served by a different server.
- Manual inclusion required: copy the beacon script into `src/index.html`.
- Or proxy `/jvmmonitor-beacon` from the Angular dev server to the Spring Boot backend.

### Scenario 5: Microservices with API Gateway

Multiple backend services behind an API gateway that also serves the frontend.

- The web probe should be enabled on the gateway service (the one serving HTML).
- API backend services do not need the web probe.
- Correlation: the JS beacon adds a session ID, and the gateway forwards it to backend services.

## Reverse Proxy Configuration

When the Java agent is deployed on the backend but a reverse proxy serves HTML to browsers, the agent cannot inject the beacon script automatically (the HTML never passes through the Servlet filter). The following configurations inject the script at the proxy level.

In all examples below, replace `http://backend:8080` with the actual address of your Spring Boot (or other Java) backend, and adjust the beacon script URL if needed.

### 1. Nginx

Use the `ngx_http_sub_module` (`sub_filter`) to rewrite HTML responses on the fly.

```nginx
server {
    listen 80;
    server_name example.com;

    # Proxy all requests to the Spring Boot backend
    location / {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;

        # Inject the beacon script before </body>
        sub_filter '</body>' '<script data-jvmmonitor>(function(){if(window.__jvmmonitor)return;window.__jvmmonitor=1;var ep="/jvmmonitor-beacon";function s(d){try{var x=new XMLHttpRequest();x.open("POST",ep,true);x.setRequestHeader("Content-Type","application/json");x.send(JSON.stringify(d))}catch(e){}}var sid=Math.random().toString(36).substr(2,9);window.addEventListener("load",function(){try{var t=performance.timing;s({t:"pageload",sid:sid,u:location.href,title:document.title,dns:t.domainLookupEnd-t.domainLookupStart,tcp:t.connectEnd-t.connectStart,ttfb:t.responseStart-t.requestStart,dom:t.domContentLoadedEventEnd-t.navigationStart,load:t.loadEventEnd-t.navigationStart})}catch(e){}});document.addEventListener("click",function(e){var el=e.target;s({t:"click",sid:sid,tag:el.tagName,id:el.id||"",cls:el.className||"",text:(el.textContent||"").substr(0,50),href:el.href||""})});var xhrOpen=XMLHttpRequest.prototype.open,xhrSend=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(m,u){this._jm=m;this._ju=u;this._jt=Date.now();return xhrOpen.apply(this,arguments)};XMLHttpRequest.prototype.send=function(){var x=this;var cb=function(){if(x.readyState===4){s({t:"ajax",sid:sid,method:x._jm,url:x._ju,status:x.status,dur:Date.now()-x._jt})}};x.addEventListener("readystatechange",cb);return xhrSend.apply(this,arguments)};if(window.fetch){var of=window.fetch;window.fetch=function(r,i){var u=typeof r==="string"?r:r.url;var m=(i&&i.method)||"GET";var t0=Date.now();return of.apply(this,arguments).then(function(resp){s({t:"fetch",sid:sid,method:m,url:u,status:resp.status,dur:Date.now()-t0});return resp}).catch(function(err){s({t:"fetch",sid:sid,method:m,url:u,status:0,dur:Date.now()-t0,err:err.message});throw err})}}window.addEventListener("error",function(e){s({t:"jserror",sid:sid,msg:e.message||"",src:e.filename||"",line:e.lineno||0,col:e.colno||0})});window.addEventListener("unhandledrejection",function(e){s({t:"jserror",sid:sid,msg:"Unhandled rejection: "+(e.reason?e.reason.message||String(e.reason):"unknown"),src:"",line:0,col:0})});var lastUrl=location.href;setInterval(function(){if(location.href!==lastUrl){s({t:"navigate",sid:sid,from:lastUrl,to:location.href});lastUrl=location.href}},500);document.addEventListener("submit",function(e){var f=e.target;s({t:"submit",sid:sid,action:f.action||"",method:f.method||"GET",id:f.id||""})})})()</script></body>';
        sub_filter_once on;
        sub_filter_types text/html;
    }

    # Forward beacon requests to the backend agent
    location /jvmmonitor-beacon {
        proxy_pass http://backend:8080/jvmmonitor-beacon;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

**Note:** The `ngx_http_sub_module` is included in most nginx distributions. Verify with `nginx -V 2>&1 | grep sub_module`.

### 2. Apache HTTP Server

Use `mod_substitute` (available in Apache 2.2+) to inject the script.

```apache
# Enable required modules:
#   a2enmod substitute proxy proxy_http headers

<VirtualHost *:80>
    ServerName example.com

    ProxyPass /jvmmonitor-beacon http://backend:8080/jvmmonitor-beacon
    ProxyPassReverse /jvmmonitor-beacon http://backend:8080/jvmmonitor-beacon

    ProxyPass / http://backend:8080/
    ProxyPassReverse / http://backend:8080/

    # Inject the beacon script into HTML responses
    AddOutputFilterByType SUBSTITUTE text/html
    Substitute "s|</body>|<script data-jvmmonitor>(function(){if(window.__jvmmonitor)return;window.__jvmmonitor=1;var ep=\"/jvmmonitor-beacon\";function s(d){try{var x=new XMLHttpRequest();x.open(\"POST\",ep,true);x.setRequestHeader(\"Content-Type\",\"application/json\");x.send(JSON.stringify(d))}catch(e){}}var sid=Math.random().toString(36).substr(2,9);window.addEventListener(\"load\",function(){try{var t=performance.timing;s({t:\"pageload\",sid:sid,u:location.href,title:document.title,dns:t.domainLookupEnd-t.domainLookupStart,tcp:t.connectEnd-t.connectStart,ttfb:t.responseStart-t.requestStart,dom:t.domContentLoadedEventEnd-t.navigationStart,load:t.loadEventEnd-t.navigationStart})}catch(e){}});document.addEventListener(\"click\",function(e){var el=e.target;s({t:\"click\",sid:sid,tag:el.tagName,id:el.id,cls:el.className,text:(el.textContent).substr(0,50),href:el.href})});})()</script></body>|i"
</VirtualHost>
```

**Note:** The Apache `Substitute` example above is abbreviated for readability. For the full beacon script, copy the complete script from the Nginx example or the Manual Script Tag section below. The `i` flag makes the match case-insensitive.

### 3. HAProxy

HAProxy operates at the TCP/HTTP layer and **cannot modify response bodies**. Use one of these alternatives:

```haproxy
frontend http_front
    bind *:80
    default_backend app_servers

    # Route beacon requests to the backend
    acl is_beacon path_beg /jvmmonitor-beacon
    use_backend app_servers if is_beacon

backend app_servers
    server backend1 backend:8080 check

    # Optional: add a CSP header to allow the beacon script
    http-response add-header Content-Security-Policy "script-src 'self' 'unsafe-inline';" if { capture.req.uri -m beg / }
```

**Recommended approach with HAProxy:** Add the beacon script tag manually to your HTML files (see the Manual Script Tag section below), and let HAProxy proxy `/jvmmonitor-beacon` requests to the backend as shown above.

### 4. Spring Cloud Gateway / Zuul

If the Java agent is deployed on the gateway itself, the webprobe module handles injection automatically -- just enable it with `enable webprobe 1`.

If the agent runs on a downstream service and you need the gateway to inject the script, use a response body modification filter:

**Spring Cloud Gateway (WebFlux):**

```java
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;

@Component
public class WebProbeInjectionFilter implements GlobalFilter, Ordered {

    private static final String BEACON_SCRIPT =
        "<script data-jvmmonitor>/* ... full beacon script ... */</script>";

    public Mono<Void> filter(
            org.springframework.web.server.ServerWebExchange exchange,
            org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        return chain.filter(exchange.mutate()
            .response(new WebProbeResponseDecorator(exchange.getResponse()))
            .build());
    }

    public int getOrder() {
        return -1;
    }

    // WebProbeResponseDecorator: wrap the response body DataBuffer,
    // replace </body> with BEACON_SCRIPT + </body> in HTML responses.
    // See Spring Cloud Gateway documentation for ModifyResponseBodyGatewayFilterFactory
    // for a complete implementation pattern.
}
```

**Netflix Zuul:**

```java
import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import java.io.InputStream;
import java.nio.charset.Charset;

public class WebProbeZuulFilter extends ZuulFilter {

    private static final String BEACON_SCRIPT =
        "<script data-jvmmonitor>/* ... full beacon script ... */</script>";

    public String filterType() { return "post"; }
    public int filterOrder() { return 100; }
    public boolean shouldFilter() {
        RequestContext ctx = RequestContext.getCurrentContext();
        String ct = ctx.getResponse().getContentType();
        return ct != null && ct.contains("text/html");
    }

    public Object run() {
        RequestContext ctx = RequestContext.getCurrentContext();
        try {
            InputStream is = ctx.getResponseDataStream();
            String body = org.apache.commons.io.IOUtils.toString(is, "UTF-8");
            body = body.replace("</body>", BEACON_SCRIPT + "</body>");
            ctx.setResponseBody(body);
        } catch (Exception e) {
            // log error
        }
        return null;
    }
}
```

### 5. Kubernetes Ingress (nginx-ingress)

Use the `configuration-snippet` annotation to add `sub_filter` directives to the nginx-ingress controller.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: myapp-ingress
  annotations:
    nginx.ingress.kubernetes.io/configuration-snippet: |
      sub_filter '</body>' '<script data-jvmmonitor>(function(){if(window.__jvmmonitor)return;window.__jvmmonitor=1;var ep="/jvmmonitor-beacon";function s(d){try{var x=new XMLHttpRequest();x.open("POST",ep,true);x.setRequestHeader("Content-Type","application/json");x.send(JSON.stringify(d))}catch(e){}}var sid=Math.random().toString(36).substr(2,9);window.addEventListener("load",function(){try{var t=performance.timing;s({t:"pageload",sid:sid,u:location.href,title:document.title,dns:t.domainLookupEnd-t.domainLookupStart,tcp:t.connectEnd-t.connectStart,ttfb:t.responseStart-t.requestStart,dom:t.domContentLoadedEventEnd-t.navigationStart,load:t.loadEventEnd-t.navigationStart})}catch(e){}});document.addEventListener("click",function(e){var el=e.target;s({t:"click",sid:sid,tag:el.tagName,id:el.id||"",cls:el.className||"",text:(el.textContent||"").substr(0,50),href:el.href||""})});var xhrOpen=XMLHttpRequest.prototype.open,xhrSend=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(m,u){this._jm=m;this._ju=u;this._jt=Date.now();return xhrOpen.apply(this,arguments)};XMLHttpRequest.prototype.send=function(){var x=this;var cb=function(){if(x.readyState===4){s({t:"ajax",sid:sid,method:x._jm,url:x._ju,status:x.status,dur:Date.now()-x._jt})}};x.addEventListener("readystatechange",cb);return xhrSend.apply(this,arguments)};if(window.fetch){var of=window.fetch;window.fetch=function(r,i){var u=typeof r==="string"?r:r.url;var m=(i&&i.method)||"GET";var t0=Date.now();return of.apply(this,arguments).then(function(resp){s({t:"fetch",sid:sid,method:m,url:u,status:resp.status,dur:Date.now()-t0});return resp}).catch(function(err){s({t:"fetch",sid:sid,method:m,url:u,status:0,dur:Date.now()-t0,err:err.message});throw err})}}window.addEventListener("error",function(e){s({t:"jserror",sid:sid,msg:e.message||"",src:e.filename||"",line:e.lineno||0,col:e.colno||0})});window.addEventListener("unhandledrejection",function(e){s({t:"jserror",sid:sid,msg:"Unhandled rejection: "+(e.reason?e.reason.message||String(e.reason):"unknown"),src:"",line:0,col:0})});var lastUrl=location.href;setInterval(function(){if(location.href!==lastUrl){s({t:"navigate",sid:sid,from:lastUrl,to:location.href});lastUrl=location.href}},500);document.addEventListener("submit",function(e){var f=e.target;s({t:"submit",sid:sid,action:f.action||"",method:f.method||"GET",id:f.id||""})})})()</script></body>';
      sub_filter_once on;
      sub_filter_types text/html;
spec:
  rules:
    - host: example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: myapp-service
                port:
                  number: 8080
```

**Note:** The nginx-ingress controller must have the `sub_filter` module available. Most default installations include it. If you use a custom nginx image, verify with `nginx -V`.

## Manual Script Tag

If you cannot use reverse proxy injection (or prefer explicit control), add the beacon script tag directly to your HTML entry point.

### The Script Tag

Copy and paste the following before the closing `</body>` tag in your HTML file:

```html
<script data-jvmmonitor>
(function(){
  if(window.__jvmmonitor) return;
  window.__jvmmonitor = 1;

  // Change this if the backend is on a different host/port
  var ep = "/jvmmonitor-beacon";

  function s(d) {
    try {
      var x = new XMLHttpRequest();
      x.open("POST", ep, true);
      x.setRequestHeader("Content-Type", "application/json");
      x.send(JSON.stringify(d));
    } catch(e) {}
  }

  var sid = Math.random().toString(36).substr(2, 9);

  // Page load timing
  window.addEventListener("load", function(){
    try {
      var t = performance.timing;
      s({t:"pageload", sid:sid, u:location.href, title:document.title,
         dns:t.domainLookupEnd - t.domainLookupStart,
         tcp:t.connectEnd - t.connectStart,
         ttfb:t.responseStart - t.requestStart,
         dom:t.domContentLoadedEventEnd - t.navigationStart,
         load:t.loadEventEnd - t.navigationStart});
    } catch(e) {}
  });

  // Click tracking
  document.addEventListener("click", function(e){
    var el = e.target;
    s({t:"click", sid:sid, tag:el.tagName, id:el.id||"",
       cls:el.className||"", text:(el.textContent||"").substr(0,50),
       href:el.href||""});
  });

  // XHR tracking
  var xhrOpen = XMLHttpRequest.prototype.open;
  var xhrSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u){
    this._jm = m; this._ju = u; this._jt = Date.now();
    return xhrOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function(){
    var x = this;
    x.addEventListener("readystatechange", function(){
      if(x.readyState === 4){
        s({t:"ajax", sid:sid, method:x._jm, url:x._ju,
           status:x.status, dur:Date.now() - x._jt});
      }
    });
    return xhrSend.apply(this, arguments);
  };

  // Fetch tracking
  if(window.fetch){
    var of = window.fetch;
    window.fetch = function(r, i){
      var u = typeof r === "string" ? r : r.url;
      var m = (i && i.method) || "GET";
      var t0 = Date.now();
      return of.apply(this, arguments).then(function(resp){
        s({t:"fetch", sid:sid, method:m, url:u,
           status:resp.status, dur:Date.now() - t0});
        return resp;
      }).catch(function(err){
        s({t:"fetch", sid:sid, method:m, url:u,
           status:0, dur:Date.now() - t0, err:err.message});
        throw err;
      });
    };
  }

  // JS error tracking
  window.addEventListener("error", function(e){
    s({t:"jserror", sid:sid, msg:e.message||"",
       src:e.filename||"", line:e.lineno||0, col:e.colno||0});
  });
  window.addEventListener("unhandledrejection", function(e){
    s({t:"jserror", sid:sid,
       msg:"Unhandled rejection: " + (e.reason ? e.reason.message || String(e.reason) : "unknown"),
       src:"", line:0, col:0});
  });

  // SPA navigation tracking
  var lastUrl = location.href;
  setInterval(function(){
    if(location.href !== lastUrl){
      s({t:"navigate", sid:sid, from:lastUrl, to:location.href});
      lastUrl = location.href;
    }
  }, 500);

  // Form submit tracking
  document.addEventListener("submit", function(e){
    var f = e.target;
    s({t:"submit", sid:sid, action:f.action||"",
       method:f.method||"GET", id:f.id||""});
  });
})()
</script>
```

### Configuring the Beacon Endpoint URL

By default the beacon posts to `/jvmmonitor-beacon` on the same origin. If the backend runs on a different host or port, change the `ep` variable at the top of the script:

```javascript
// Backend on a different port (development)
var ep = "http://localhost:8080/jvmmonitor-beacon";

// Backend on a different host (production)
var ep = "https://api.example.com/jvmmonitor-beacon";
```

When using a cross-origin endpoint, the backend must include the appropriate CORS headers. The Java agent's beacon handler returns these automatically when it detects a cross-origin request.

### Framework-Specific Placement

**Angular:** Add the script tag to `src/index.html` before `</body>`:

```html
<!-- src/index.html -->
<body>
  <app-root></app-root>
  <script data-jvmmonitor>/* ... beacon script ... */</script>
</body>
```

**React:** Add the script tag to `public/index.html` before `</body>`:

```html
<!-- public/index.html -->
<body>
  <noscript>You need to enable JavaScript to run this app.</noscript>
  <div id="root"></div>
  <script data-jvmmonitor>/* ... beacon script ... */</script>
</body>
```

**Vue:** Add the script tag to `public/index.html` before `</body>`:

```html
<!-- public/index.html -->
<body>
  <div id="app"></div>
  <script data-jvmmonitor>/* ... beacon script ... */</script>
</body>
```

For all three frameworks, if you use a development proxy (Angular CLI, Create React App, Vue CLI), also configure the proxy to forward `/jvmmonitor-beacon` to your backend. For example, in Angular's `proxy.conf.json`:

```json
{
  "/jvmmonitor-beacon": {
    "target": "http://localhost:8080",
    "secure": false
  }
}
```

## Data Format

Each user action event has the following fields:

| Field | Description |
|---|---|
| **Type** | USER (event type 20) |
| **Thread** | `browser:<sessionId>` -- unique per browser tab |
| **Class** | Action type: click, ajax, fetch, pageload, jserror, navigate, submit |
| **Context** | Human-readable description (see examples below) |

### Context Examples

| Action | Example Context |
|---|---|
| Click | `CLICK BUTTON#submit-btn "Place Order" -> /checkout` |
| AJAX | `POST /api/orders 201 145ms` |
| Fetch | `GET /api/products 200 89ms` |
| Page load | `PAGE My App - Orders ttfb=23ms dom=450ms load=890ms` |
| JS Error | `JS ERROR: Cannot read property 'x' of null at app.js:42` |
| Navigate | `NAVIGATE /products -> /cart` |
| Form submit | `FORM SUBMIT POST /api/login` |

## Viewing Data

### GUI

Open the **Instrumentation** tab and select the **All Events** sub-tab. Filter by Type = "USER" to see only browser user action events.

### CLI

```
jvm-monitor> instrument events
```

This shows all instrumentation events including user actions from the Web Probe.

### Session Export

User action events are included in saved sessions (`.jvmsession.gz` files) and in HTML reports generated via **Tools > Session > Export HTML Report**.

## Performance Impact

- **Zero impact** when not enabled.
- **When enabled**: approximately 1 KB extra per HTML response (script injection) plus approximately 200 bytes per beacon POST request.
- The beacon uses async XMLHttpRequest -- it does not block the browser UI.
- Beacons are fire-and-forget: the agent returns a 204 response immediately, no retry logic.

## Limitations

- Cannot capture actions in iframes from different origins (browser same-origin policy).
- Cannot capture file downloads or browser extension interactions.
- Page load timing uses the Navigation Timing API (Level 1) -- available in all modern browsers.
- The injected script is visible in the page source (search for `data-jvmmonitor` to find it).
- Only works with the Java agent. The native C agent does not support the web probe.
