package uk.gov.dwp.uc.pairtest;

import thirdparty.paymentgateway.TicketPaymentService;
import thirdparty.seatbooking.SeatReservationService;
import uk.gov.dwp.uc.pairtest.domain.TicketTypeRequest;
import uk.gov.dwp.uc.pairtest.domain.TicketTypeRequest.Type;
import uk.gov.dwp.uc.pairtest.exception.InvalidPurchaseException;

import java.util.Arrays;

public class TicketServiceImpl implements TicketService {

    private static final int MAX_TICKETS_PER_PURCHASE = 25;
    private static final int ADULT_TICKET_PRICE = 25;
    private static final int CHILD_TICKET_PRICE = 15;

    // Package-private so the tests can assert the exact rejection reason.
    static final String INVALID_ACCOUNT_MESSAGE = "Account id must be a positive number";
    static final String NO_REQUESTS_MESSAGE = "At least one ticket request is required";
    static final String NULL_REQUEST_MESSAGE = "Ticket request must not be null";
    static final String NO_TICKET_TYPE_MESSAGE = "Ticket request must have a ticket type";
    static final String NEGATIVE_TICKETS_MESSAGE = "Number of tickets cannot be negative";
    static final String NO_TICKETS_MESSAGE = "At least one ticket must be purchased";
    static final String TOO_MANY_TICKETS_MESSAGE =
            "Cannot purchase more than " + MAX_TICKETS_PER_PURCHASE + " tickets at a time";
    static final String NO_ADULT_MESSAGE =
            "Child and infant tickets cannot be purchased without an adult ticket";
    static final String TOO_MANY_INFANTS_MESSAGE =
            "Each infant must sit on an adult's lap, so infants cannot outnumber adults";

    private final TicketPaymentService paymentService;
    private final SeatReservationService seatReservationService;

    public TicketServiceImpl(TicketPaymentService paymentService,
                             SeatReservationService seatReservationService) {
        this.paymentService = paymentService;
        this.seatReservationService = seatReservationService;
    }

    @Override
    public void purchaseTickets(Long accountId, TicketTypeRequest... ticketTypeRequests) throws InvalidPurchaseException {
        validateAccountId(accountId);
        validateRequests(ticketTypeRequests);

        int adultTickets = countTicketsOfType(Type.ADULT, ticketTypeRequests);
        int childTickets = countTicketsOfType(Type.CHILD, ticketTypeRequests);
        int infantTickets = countTicketsOfType(Type.INFANT, ticketTypeRequests);

        validatePurchaseRules(adultTickets, childTickets, infantTickets);

        // Infants sit on an adult's lap, so they are not charged and do not take a seat.
        int totalAmountToPay = (adultTickets * ADULT_TICKET_PRICE) + (childTickets * CHILD_TICKET_PRICE);
        int totalSeatsToReserve = adultTickets + childTickets;

        paymentService.makePayment(accountId, totalAmountToPay);
        seatReservationService.reserveSeat(accountId, totalSeatsToReserve);
    }

    private void validateAccountId(Long accountId) {
        if (accountId == null || accountId <= 0) {
            throw new InvalidPurchaseException(INVALID_ACCOUNT_MESSAGE);
        }
    }

    private void validateRequests(TicketTypeRequest[] requests) {
        if (requests == null || requests.length == 0) {
            throw new InvalidPurchaseException(NO_REQUESTS_MESSAGE);
        }
        for (TicketTypeRequest request : requests) {
            if (request == null) {
                throw new InvalidPurchaseException(NULL_REQUEST_MESSAGE);
            }
            if (request.getTicketType() == null) {
                throw new InvalidPurchaseException(NO_TICKET_TYPE_MESSAGE);
            }
            if (request.getNoOfTickets() < 0) {
                throw new InvalidPurchaseException(NEGATIVE_TICKETS_MESSAGE);
            }
            if (request.getNoOfTickets() > MAX_TICKETS_PER_PURCHASE) {
                throw new InvalidPurchaseException(TOO_MANY_TICKETS_MESSAGE);
            }
        }
    }

    private int countTicketsOfType(Type type, TicketTypeRequest[] requests) {
        return Arrays.stream(requests)
                .filter(request -> request.getTicketType() == type)
                .mapToInt(TicketTypeRequest::getNoOfTickets)
                .sum();
    }

    private void validatePurchaseRules(int adultTickets, int childTickets, int infantTickets) {
        int totalTickets = adultTickets + childTickets + infantTickets;
        if (totalTickets == 0) {
            throw new InvalidPurchaseException(NO_TICKETS_MESSAGE);
        }
        if (totalTickets > MAX_TICKETS_PER_PURCHASE) {
            throw new InvalidPurchaseException(TOO_MANY_TICKETS_MESSAGE);
        }
        if (adultTickets == 0) {
            throw new InvalidPurchaseException(NO_ADULT_MESSAGE);
        }
        if (infantTickets > adultTickets) {
            throw new InvalidPurchaseException(TOO_MANY_INFANTS_MESSAGE);
        }
    }

}
