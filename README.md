[![Build Status](https://travis-ci.org/cmiles74/bishop.png?branch=master)](https://travis-ci.org/cmiles74/bishop)

Bishop is a [Webmachine](http://wiki.basho.com/Webmachine.html)-like
library for Clojure. Bishop provides tools that make it easy and
straightforward for your web-service to treat
[HTTP](http://en.wikipedia.org/wiki/Hypertext_Transfer_Protocol) as a
first-class application protocol. The library handles things like
content negotiation and predictable caching behavior, leaving you to
concentrate on a building a clean and consistent API be it
[REST-ful](http://en.wikipedia.org/wiki/REST) or even
[HATEOAS](http://en.wikipedia.org/wiki/HATEOAS) compliant.

When you create a “resource” with Bishop, you receive a function that
expects a map of request values and will return a map of response
values. This library was designed to be used with
[Ring](https://github.com/mmcgrana/ring) and should work with any Ring
middle-ware. Bishop provides its own routing mechanism but you can use
another if you like (for instance
[Moustache](https://github.com/cgrand/moustache)).

This is our first release of this library and there may be bugs that
need squashing, please
[register an issue](https://github.com/cmiles74/bishop/issues) if
you notice any or send us a pull request if you fix them. We’re also
providing
[a sample application](https://github.com/cmiles74/bishop-sample)
that provides a more in-depth example. We’re working on implementing
an application for production use that leverages this library, we
expect to be polishing it further over the coming months. If you find
it useful in any way, please feel free to...

<a href="https://www.buymeacoffee.com/cmiles74" target="_blank"><img src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png" alt="Buy Me A Coffee" style="height: 41px !important;width: 174px !important;box-shadow: 0px 3px 2px 0px rgba(190, 190, 190, 0.5) !important;-webkit-box-shadow: 0px 3px 2px 0px rgba(190, 190, 190, 0.5) !important;" ></a>

## Aren't There Other Projects that Do This?

Yes, there are several other projects that are looking to do this
very same thing. The ones that I am aware of are...

*  [Clothesline](https://github.com/banjiewen/Clothesline)
*  [Plugboard](https://github.com/malcolmsparks/plugboard)

This project has slightly different goals from those mentioned
above. For one, this project isn't particularly interested in
exposing a nice interface to Java code. Our primary concern is to
make things easier for the Clojure developer.

Plugboard is constructed on top of the excellent
[Compojure](https://github.com/weavejester/compojure) library which in
turn builds on Ring, this project instead builds on top of Ring
directly. The web APIs that I have constructed so far have been coded
on Ring and I didn't want to pull Compojure into the mix.

## Breaking Changes from 1.1.9 to 1.2.0

The way routing is handled has been changed from version 1.2.0
forward. Earlier versions of Bishop used a map for routing and this
did not allow for the routing rules to be provided in any specific
order (i.e., the wildcard route is last so only use it if nothing else
matches). While this worked fine for smaller applications, it makes
more sense to provide ordered routes. From version 1.2.0 forward,
routes are now specified as a sequence.

## Installation

To use Bishop, add the following to your project’s “:dependencies”:

```clojure
[tnrglobal/bishop "1.2.0"]
```

## How Does it Work?

Anyway, let's say you have a function that will say "Hello" to
people. Add the `com.tnrglobal.bishop.core` namespace to your project.

```clojure
(ns hello.core
  (:require [com.tnrglobal.bishop.core :as bishop]))
```
We also define the function that does our work.

```clojure
(defn hello
  [name]
  (str "Hello " name "!"))
```

We can then define a resource that says "Hello" in HTML or JSON. In
this example we use [Hiccup](https://github.com/weavejester/hiccup) to
generate our HTML and [CLJ-JSON](https://github.com/mmcgrana/clj-json)
to generate our JSON output.

```clojure
(def hello-resource
  (bishop/resource
    {"text/html" (fn [request]
      (hiccup/html
        [:p (hello (:name (:path-info request)))]))}

    {"text/json" (fn [request]
      {:body (clj-json/generate-string
               {:message (hello (:name (:path-info request)))}))}))
```

This resource can return either HTML or JSON content, depending on the
“Accept” headers of the request. It expects to have a value in the
"path-info" map under the ":name" key. This comes from the routing.

```clojure
(defroutes routes
  ["hello" :name] hello-resource
  ["*"] (bishop/halt-resource 404))
```

We route incoming request for "/hello/something" to our
"hello-resource" functions, anything else will result in sending a
"404" code to the client. Bishop will parse the route and the
request's URI to populate the "path-info" map for your application,
the goal is to do it in the same way that
[Webmachine handles dispatch](http://wiki.basho.com/Webmachine-Dispatching.html).

Lastly, you can add this as your Ring handler function.

```clojure
(def app
  (-> (bishop/handler #'routes)))
```

In this example we pass our routes to the handler as a var, this is
done so that changes to the routes are visible in a running REPL
session or through Ring's reloading middleware.

## Using Another Routing Library


If you'd like to use another routing library, you may use the
"raw-handler" function instead. This will provide you with a Ring
handler that simply applies the incoming request to the Bishop
resource. For instance, you might prefer
[Moustache](https://github.com/cgrand/moustache).
```clojure
(def hello-resource
  (bishop/raw-handler
    (bishop/resource {"text/html"
	                  (fn [request]
					    (hiccup/html
						  [:p (hello name)]))})))

(def moustache-handler
  (moustache/app
    ["hello" name] hello-resource
	[&] (bishop/raw-handler
	      (bishop/halt-resource 404))))

(def app
  (-> moustache-handler))
```
Instead of asking Bishop to provide a resource equipped to handle it's
own routing, we ask for a "raw" handler that expects routing to
already have been handled. We can then plug-in Moustache and provide
our Bishop resources as end-points. More examples are available in the
[unit tests](https://github.com/cmiles74/bishop/blob/master/test/com/tnrglobal/bishop/test/core.clj#L25).

## What Else Does it Do?

Aside from parsing the URI and matching it to the route, Bishop is
doing a lot of other work as well. It covers all of the behavior in
this
[HTTP 1.1 flow chart](http://wiki.basho.com/Webmachine-Diagram.html),
it does this by providing a state-machine that implements the decision
tree. In our example, Bishop is...

* Parsing the client URI and route, then populating the "path-info"
map

* Verifying that the client is trying to either GET or HEAD the
resource.

* Making sure that the length of the URI isn't totally nutty.

* Verifying that our resource can accept either a GET or a HEAD
request.

* Selecting the appropriate content type to provide to the client and
sending the appropriate error if the client asks for a resource that
we do not provide.

And so on.

## Sample Application

We have put a small,
[sample application](https://github.com/cmiles74/bishop-sample) that
provides a more in-depth example. You may find it useful to look the
sample code over to get a better idea of how Bishop functions.

[https://github.com/tnr-global/bishop-sample](https://github.com/cmiles74/bishop-sample)
