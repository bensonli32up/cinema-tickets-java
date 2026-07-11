# Cinema Tickets Java Exercise

This is my implementation of the DWP cinema tickets coding exercise.

## Requirements

* JDK 21
* Maven 3.9 or later

## How to run the tests

```bash
mvn clean test
```

There is no main class in this project. The service is a library class, so the way to see it
working is to run the tests.

## Where to look

* Implementation: `src/main/java/uk/gov/dwp/uc/pairtest/TicketServiceImpl.java`
* Tests: `src/test/java/uk/gov/dwp/uc/pairtest/TicketServiceImplTest.java`
* Everything under `thirdparty` and the `TicketService` interface is provided code from the
  exercise, and I have not changed them.

## Business rules

| Ticket type | Price | Seat allocated |
| ----------- | ----- | -------------- |
| ADULT       | £25   | Yes            |
| CHILD       | £15   | Yes            |
| INFANT      | £0    | No (sits on an adult's lap) |

* Total to pay = adults × £25 + children × £15. Infants are free.
* Seats to reserve = adults + children. Infants do not get a seat.
* Maximum 25 tickets in one purchase.
* Child and infant tickets cannot be bought without at least one adult ticket.

A quick example: 2 adults + 3 children + 1 infant

* Payment: (2 × £25) + (3 × £15) = £95
* Seats: 2 + 3 = 5 (the infant has no seat)

## Approach

`TicketServiceImpl` does three things, in this order:

1. Validate the request
2. Calculate the total amount and the number of seats
3. Make one call to `TicketPaymentService`, then one call to `SeatReservationService`

Some notes on the design:

* The two third-party services are passed in through the constructor. This makes the class
  easy to unit test with mocks.
* Ticket prices and the 25-ticket limit are named constants, so a price change is a one-line
  edit.
* If the caller sends more than one request for the same type (for example ADULT × 2 and
  ADULT × 3 in the same call), the counts are added together first. All the rules are checked
  against the combined totals.
* Payment is made before seat reservation. Under the brief's assumptions both calls always
  succeed, so the order does not change the result here — I simply followed the order the
  brief lists them. In a real system it would likely be the other way round (hold the seats
  first, then take the payment).

## Changes to the provided classes

The brief only forbids changing the `TicketService` interface and the `thirdparty.*`
packages. I did change two other provided classes, on purpose:

* `TicketTypeRequest` — the provided class is already immutable in practice: the fields are
  private, set once in the constructor, and there are no setters. But the fields are not
  `final`, so nothing stops a future change from breaking that, and non-final fields also
  miss the safe publication guarantee that the Java Memory Model gives to `final` fields
  when an object is shared between threads. I made the two fields `final`, so the class is
  strictly immutable as the brief asks.
* `InvalidPurchaseException` — I added a constructor that takes a message, so a rejected
  purchase always comes with the reason.

## Invalid purchases

The brief leaves it to the implementer to define what is invalid. In this solution the
following cases are rejected, and no call is made to either third-party service:

* The account id is null, zero, or negative
* There are no ticket requests at all (null or empty)
* A ticket request is null
* A ticket request has no ticket type
* A ticket request has a negative number of tickets
* A single request asks for more than 25 tickets by itself (it can never be part of a valid
  purchase, and rejecting it early also protects the summed totals from integer overflow)
* The total number of tickets is zero
* More than 25 tickets are requested in one purchase
* Child or infant tickets are requested without at least one adult ticket
* There are more infants than adults

One small point: a request for zero tickets of a type (for example CHILD × 0 next to
ADULT × 2) is allowed — it just adds nothing. The purchase as a whole must still be valid.

The checks run in the order listed above, so if a request breaks more than one rule, the
exception message comes from the first check that fails.

## Testing

I used JUnit 5 and Mockito. The two third-party services are mocked, so the tests can check
the real behaviour of the service, not just that an exception is thrown:

* the exact amount passed to `TicketPaymentService` for a given purchase
* the exact seat count passed to `SeatReservationService`
* that infants are not charged and get no seat
* that every invalid case is rejected with the expected reason, and neither service is
  called at all
* the boundary cases: 25 tickets is accepted, 26 is rejected (single type and mixed types)

## Assumptions

Two points are not fully clear in the brief. I raised both with the recruitment team before
starting. They could not give extra guidance, to keep the process fair for all candidates.
So I made the following assumptions and applied them consistently in the code and tests.

### Infant tickets count towards the 25-ticket limit

An infant ticket is still a ticket request, even though it costs £0 and does not need a
seat. For example, 20 adults and 6 infants is treated as 26 tickets, so the purchase is
rejected.

### One infant per adult

The brief says an infant sits on an adult's lap. I read this as one infant per adult. For
example, 1 adult and 2 infants is rejected.

## Production considerations (out of scope for this exercise)

The brief says both third-party services always succeed once called, and the exercise is
focused on clean code. So I kept the solution simple on purpose. But in a real production
system, I would also think about the areas below.

### Calling external services

`TicketPaymentService` and `SeatReservationService` would probably be remote calls. Real
calls fail, so I would want:

* timeouts
* retries with idempotency keys, so a retry cannot take payment twice
* structured logging, metrics and monitoring
* handling of partial failure — for example payment taken but seats not reserved. That needs
  some compensation step, like an automatic refund, or a saga-style flow where the seats are
  held first before the payment is captured.

### Money handling

I would normally not use primitive `int` for money — `BigDecimal` or a dedicated money type
in minor units is safer. In this exercise the payment API accepts an `int`, all prices are
whole pounds, and the interface cannot be changed, so I used `int` to match the existing API.

### Error messages and localisation

The rejection messages are shared constants in `TicketServiceImpl`, and the tests assert
them, so each test proves which rule rejected the purchase.

In a bigger system I would not keep user-facing text in code. The usual pattern is that the
exception carries a stable error code, and the presentation layer turns that code into the
user's language, for example with `ResourceBundle`. Exception messages and logs stay in
English so they are easy to search. This exercise has no user interface, so simple constants
are enough here.

### Concurrency and stock

Seats are not unlimited in real life. When many customers buy at the same time, the system
must not oversell — that usually needs atomic seat management, or a hold-and-confirm flow
with expiring holds. The provided `reserveSeat` API has no screening, seat numbers or
capacity, so this is outside the scope of the exercise.

`TicketServiceImpl` itself keeps no state (no mutable fields), so the class itself does not
add any thread-safety problems. But that alone does not prevent overselling — that part
depends on the seat reservation system behind it.

### Authorisation

In this exercise the caller passes in the `accountId` directly. A real system should not
trust that — the user should be authenticated first, and the account id should come from the
session or token. Rate limiting and an audit trail for purchases would also be sensible.
