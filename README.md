# chat-example

A Clojure chat server made in clojure using redis ([carmine](https://github.com/ptaoussanis/carmine)) pub/sub to communicate b/w nodes and for session storage, [Http-Kit](http://http-kit.org/) as http server and [sqlkorma](http://sqlkorma.com/) with mysql.

### Do not use in production!!, this project does not use standard security measures for user authentication (this uses plain text password matching). This was hacked together in a few days for a small demo. Again do not use in production.
