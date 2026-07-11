package uk.gov.dwp.uc.pairtest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import thirdparty.paymentgateway.TicketPaymentService;
import thirdparty.seatbooking.SeatReservationService;
import uk.gov.dwp.uc.pairtest.domain.TicketTypeRequest;
import uk.gov.dwp.uc.pairtest.domain.TicketTypeRequest.Type;
import uk.gov.dwp.uc.pairtest.exception.InvalidPurchaseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static uk.gov.dwp.uc.pairtest.TicketServiceImpl.*;

class TicketServiceImplTest {

    private static final long ACCOUNT_ID = 42L;

    private TicketPaymentService paymentService;
    private SeatReservationService seatReservationService;
    private TicketServiceImpl ticketService;

    @BeforeEach
    void setUp() {
        paymentService = mock(TicketPaymentService.class);
        seatReservationService = mock(SeatReservationService.class);
        ticketService = new TicketServiceImpl(paymentService, seatReservationService);
    }

    // --- valid purchases ---

    @Test
    void chargesCorrectAmountAndReservesSeatsForMixedPurchase() {
        ticketService.purchaseTickets(ACCOUNT_ID,
                tickets(Type.ADULT, 2), tickets(Type.CHILD, 3), tickets(Type.INFANT, 1));

        verify(paymentService).makePayment(ACCOUNT_ID, 95); // 2 x 25 + 3 x 15
        verify(seatReservationService).reserveSeat(ACCOUNT_ID, 5); // infant gets no seat
    }

    @Test
    void processesAnAdultOnlyPurchase() {
        ticketService.purchaseTickets(ACCOUNT_ID, tickets(Type.ADULT, 1));

        verify(paymentService).makePayment(ACCOUNT_ID, 25);
        verify(seatReservationService).reserveSeat(ACCOUNT_ID, 1);
    }

    @Test
    void infantsAreNotChargedAndNotAllocatedSeats() {
        // also covers the boundary where infants equal adults, which is allowed
        ticketService.purchaseTickets(ACCOUNT_ID, tickets(Type.ADULT, 2), tickets(Type.INFANT, 2));

        verify(paymentService).makePayment(ACCOUNT_ID, 50);
        verify(seatReservationService).reserveSeat(ACCOUNT_ID, 2);
    }

    @Test
    void combinesMultipleRequestsForTheSameType() {
        ticketService.purchaseTickets(ACCOUNT_ID,
                tickets(Type.ADULT, 1), tickets(Type.ADULT, 2), tickets(Type.CHILD, 1));

        verify(paymentService).makePayment(ACCOUNT_ID, 90); // 3 x 25 + 1 x 15
        verify(seatReservationService).reserveSeat(ACCOUNT_ID, 4);
    }

    @Test
    void allowsZeroCountRequestAlongsideValidOnes() {
        ticketService.purchaseTickets(ACCOUNT_ID, tickets(Type.ADULT, 2), tickets(Type.CHILD, 0));

        verify(paymentService).makePayment(ACCOUNT_ID, 50);
        verify(seatReservationService).reserveSeat(ACCOUNT_ID, 2);
    }

    @Test
    void allowsPurchaseOfExactlyTwentyFiveTickets() {
        ticketService.purchaseTickets(ACCOUNT_ID, tickets(Type.ADULT, 25));

        verify(paymentService).makePayment(ACCOUNT_ID, 625);
        verify(seatReservationService).reserveSeat(ACCOUNT_ID, 25);
    }

    @Test
    void allowsExactlyTwentyFiveTicketsAcrossMixedTypes() {
        ticketService.purchaseTickets(ACCOUNT_ID,
                tickets(Type.ADULT, 20), tickets(Type.CHILD, 4), tickets(Type.INFANT, 1));

        verify(paymentService).makePayment(ACCOUNT_ID, 560); // 20 x 25 + 4 x 15
        verify(seatReservationService).reserveSeat(ACCOUNT_ID, 24); // infant gets no seat
    }

    @Test
    void takesPaymentBeforeReservingSeats() {
        ticketService.purchaseTickets(ACCOUNT_ID, tickets(Type.ADULT, 1));

        InOrder inOrder = inOrder(paymentService, seatReservationService);
        inOrder.verify(paymentService).makePayment(ACCOUNT_ID, 25);
        inOrder.verify(seatReservationService).reserveSeat(ACCOUNT_ID, 1);
    }

    // --- invalid purchases ---

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, -100L})
    void rejectsInvalidAccountIds(Long accountId) {
        InvalidPurchaseException exception = assertThrows(InvalidPurchaseException.class,
                () -> ticketService.purchaseTickets(accountId, tickets(Type.ADULT, 1)));

        assertEquals(INVALID_ACCOUNT_MESSAGE, exception.getMessage());
        verifyNoInteractions(paymentService, seatReservationService);
    }

    @Test
    void rejectsNullRequestArray() {
        InvalidPurchaseException exception = assertThrows(InvalidPurchaseException.class,
                () -> ticketService.purchaseTickets(ACCOUNT_ID, (TicketTypeRequest[]) null));

        assertEquals(NO_REQUESTS_MESSAGE, exception.getMessage());
        verifyNoInteractions(paymentService, seatReservationService);
    }

    @Test
    void rejectsPurchaseWithNoTicketRequests() {
        assertRejected(NO_REQUESTS_MESSAGE);
    }

    @Test
    void rejectsNullTicketRequest() {
        assertRejected(NULL_REQUEST_MESSAGE, tickets(Type.ADULT, 1), null);
    }

    @Test
    void rejectsRequestWithNoTicketType() {
        assertRejected(NO_TICKET_TYPE_MESSAGE, new TicketTypeRequest(null, 1));
    }

    @Test
    void rejectsNegativeTicketCounts() {
        assertRejected(NEGATIVE_TICKETS_MESSAGE,
                tickets(Type.ADULT, 2), tickets(Type.CHILD, -1));
    }

    @Test
    void rejectsPurchaseOfZeroTicketsInTotal() {
        assertRejected(NO_TICKETS_MESSAGE, tickets(Type.ADULT, 0));
    }

    @Test
    void rejectsPurchaseOfMoreThanTwentyFiveTickets() {
        assertRejected(TOO_MANY_TICKETS_MESSAGE, tickets(Type.ADULT, 26));
    }

    @Test
    void infantsCountTowardsTheMaximumTicketLimit() {
        // 26 in total, so this must fail the ticket limit, not the infant ratio rule
        assertRejected(TOO_MANY_TICKETS_MESSAGE,
                tickets(Type.ADULT, 15), tickets(Type.INFANT, 11));
    }

    @Test
    void rejectsHugeRequestsThatCouldOverflowTheTotals() {
        // summed as int these would wrap around to 10 tickets; each request is
        // rejected on its own before any summing happens
        assertRejected(TOO_MANY_TICKETS_MESSAGE,
                tickets(Type.ADULT, Integer.MAX_VALUE),
                tickets(Type.ADULT, Integer.MAX_VALUE),
                tickets(Type.ADULT, 12));
    }

    @Test
    void rejectsChildTicketsWithoutAnAdult() {
        assertRejected(NO_ADULT_MESSAGE, tickets(Type.CHILD, 1));
    }

    @Test
    void rejectsInfantTicketsWithoutAnAdult() {
        assertRejected(NO_ADULT_MESSAGE, tickets(Type.INFANT, 1));
    }

    @Test
    void rejectsChildAndInfantTicketsWithoutAnAdult() {
        assertRejected(NO_ADULT_MESSAGE,
                tickets(Type.CHILD, 2), tickets(Type.INFANT, 1));
    }

    @Test
    void zeroCountAdultRequestDoesNotSatisfyTheAdultRule() {
        assertRejected(NO_ADULT_MESSAGE, tickets(Type.ADULT, 0), tickets(Type.CHILD, 1));
    }

    @Test
    void rejectsMoreInfantsThanAdults() {
        assertRejected(TOO_MANY_INFANTS_MESSAGE,
                tickets(Type.ADULT, 1), tickets(Type.INFANT, 2));
    }

    // --- helpers ---

    private void assertRejected(String expectedReason, TicketTypeRequest... requests) {
        InvalidPurchaseException exception = assertThrows(InvalidPurchaseException.class,
                () -> ticketService.purchaseTickets(ACCOUNT_ID, requests));

        assertEquals(expectedReason, exception.getMessage());
        verifyNoInteractions(paymentService, seatReservationService);
    }

    private TicketTypeRequest tickets(Type type, int noOfTickets) {
        return new TicketTypeRequest(type, noOfTickets);
    }

}
