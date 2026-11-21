# EventPulse

**Flash-Sale Ticket & Seat-Locking Engine (Java 21 + Vanilla JavaScript)**

EventPulse prevents race conditions and double-sold seats during high-concurrency ticket flash sales. Built on a **Java 21 Virtual Threads** HTTP server, **SQLite WAL** transactional locking, and a live-updating **JavaScript** interactive auditorium seat map with 90-second TTL hold countdowns and automated hold expiration recovery.

## Quickstart

~~~bash
git clone https://github.com/itsmerbk/EventPulse.git
cd EventPulse

mkdir -p out
javac -cp "lib/*" -d out src/com/eventpulse/*.java

# 1. Run atomic seat-lock, session ownership & TTL expiry assertions
java -ea --enable-native-access=ALL-UNNAMED -cp "out:lib/*" com.eventpulse.EventPulseTest

# 2. Start the live Box Office Console (http://127.0.0.1:8086)
java --enable-native-access=ALL-UNNAMED -cp "out:lib/*" com.eventpulse.Main
~~~
