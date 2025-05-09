package rs.raf.bank_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import rs.raf.bank_service.client.UserClient;
import rs.raf.bank_service.domain.dto.*;
import rs.raf.bank_service.domain.entity.Account;
import rs.raf.bank_service.domain.entity.Card;
import rs.raf.bank_service.domain.entity.CardRequest;
import rs.raf.bank_service.domain.enums.*;
import rs.raf.bank_service.domain.mapper.AccountMapper;
import rs.raf.bank_service.domain.mapper.CardMapper;
import rs.raf.bank_service.exceptions.*;
import rs.raf.bank_service.repository.AccountRepository;
import rs.raf.bank_service.repository.CardRepository;
import rs.raf.bank_service.repository.CardRequestRepository;
import rs.raf.bank_service.security.JwtAuthenticationFilter;
import rs.raf.bank_service.utils.JwtTokenUtil;

import javax.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class CardService {
    private final CardRepository cardRepository;
    private final UserClient userClient;
    private final RabbitTemplate rabbitTemplate;
    private final AccountRepository accountRepository;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtTokenUtil jwtTokenUtil;
    private final CardRequestRepository CardRequestRepository;
    private final CardRequestRepository cardRequestRepository;
    AccountMapper accountMapper;
    ObjectMapper objectMapper;

    public static String generateCVV() {
        Random random = new Random();
        int cvv = 100 + random.nextInt(900);
        return String.valueOf(cvv);
    }

    private boolean isBusiness(AccountTypeDto accountTypeDto) {
        return accountTypeDto.getSubtype().equals(AccountOwnerType.COMPANY);
    }

    public CardDtoNoOwner createCard(CreateCardDto createCardDto) {
        Account account = accountRepository.findByAccountNumber(createCardDto.getAccountNumber())
                .orElseThrow(() -> new EntityNotFoundException("Account with account number: " + createCardDto.getAccountNumber() + " not found"));
        AccountTypeDto accountTypeDto = accountMapper.toAccountTypeDto(account);

        Long cardCount = cardRepository.countByAccount(account);

        if ((isBusiness(accountTypeDto) && cardCount > 0) || (!isBusiness(accountTypeDto) && cardCount > 2)) {
            throw new CardLimitExceededException(accountTypeDto.getAccountNumber());
        }
        if (createCardDto.getCardLimit() != null && createCardDto.getCardLimit().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidCardLimitException();
        }

        Card card = new Card();

        card.setCreationDate(LocalDate.now());
        card.setExpirationDate(LocalDate.now().plusMonths(60));
        card.setIssuer(createCardDto.getIssuer());
        card.setCardNumber(generateCardNumber(createCardDto.getIssuer()));
        card.setCvv(generateCVV());
        card.setType(createCardDto.getType());
        card.setName(createCardDto.getName());
        card.setAccount(account);
        card.setStatus(CardStatus.ACTIVE);
        card.setCardLimit(createCardDto.getCardLimit());

        cardRepository.save(card);

        return CardMapper.toCardDtoNoOwner(card);
    }

    private String generateCardNumber(CardIssuer issuer) {
        String firstFifteen = generateMIIandIIN(issuer) + generateAccountNumber();
        return firstFifteen + luhnDigit(firstFifteen);
    }

    private String generateMIIandIIN(CardIssuer issuer) {
        Random random = new Random();

        switch (issuer) {
            case VISA:
                return "433333";
            case MASTERCARD:
                if (random.nextBoolean()) {
                    return 51 + random.nextInt(5) + "3333";
                } else {
                    return 2221 + random.nextInt(500) + "33";
                }
            case DINA:
                return "989133";
            case AMERICAN_EXPRESS:
                if (random.nextBoolean()) {
                    return "343333";
                } else {
                    return "373333";
                }
            default:
                throw new IllegalArgumentException("Unsupported card type");
        }
    }

    private String generateAccountNumber() {
        Random random = new Random();

        int accountNumber = random.nextInt(1000000000);
        return String.format("%09d", accountNumber);
    }


    private String luhnDigit(String firstFifteen) {
        int sum = 0;
        boolean shouldDouble = true;

        for (int i = firstFifteen.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(firstFifteen.charAt(i));

            if (shouldDouble) {
                digit = digit * 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }

            sum = sum + digit;
            shouldDouble = !shouldDouble;
        }

        int checkDigit = (10 - (sum % 10)) % 10;
        return String.valueOf(checkDigit);
    }


    public List<CardDto> getCardsByAccount(
            @Parameter(description = "Account number to search for", example = "222222222222222222") String accountNumber) {
        List<Card> cards = cardRepository.findByAccount_AccountNumber(accountNumber);
        return cards.stream().map(card -> {
            ClientDto client = userClient.getClientById(card.getAccount().getClientId());
            return CardMapper.toDto(card, client);
        }).collect(Collectors.toList());
    }

    public void changeCardStatus(
            @Parameter(description = "Card number", example = "1234123412341234") String cardNumber,
            @Parameter(description = "New status for the card", example = "BLOCKED") CardStatus newStatus) {
        Card card = cardRepository.findByCardNumber(cardNumber)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Card not found with number: " + cardNumber));

        card.setStatus(newStatus);
        cardRepository.save(card);

        ClientDto owner = userClient.getClientById(card.getAccount().getClientId());

        EmailRequestDto emailRequestDto = new EmailRequestDto();
        emailRequestDto.setCode(newStatus.toString());
        emailRequestDto.setDestination(owner.getEmail());

        rabbitTemplate.convertAndSend("card-status-change", emailRequestDto);
    }

    public List<CardDto> getUserCards(String authHeader) {

        Long clientId = jwtTokenUtil.getUserIdFromAuthHeader(authHeader);

        List<Account> userAccounts = accountRepository.findByClientId(clientId);

        List<Card> userCards = userAccounts.stream()
                .flatMap(account -> account.getCards().stream())
                .toList();

        ClientDto owner = userClient.getClientById(clientId);
        return userCards.stream()
                .map(card -> CardMapper.toDto(card, owner))
                .collect(Collectors.toList());
    }

    public List<CardDto> getUserCardsForAccount(String accountNumber, String authHeader) {
        Long clientId = jwtTokenUtil.getUserIdFromAuthHeader(authHeader);

        // check if account exists and is owned by client
        accountRepository.findByAccountNumberAndClientId(accountNumber, clientId).orElseThrow(AccountNotFoundException::new);

        List<Card> userCards = cardRepository.findByAccount_AccountNumber(accountNumber);

        ClientDto owner = userClient.getClientById(clientId);
        return userCards.stream()
                .map(card -> CardMapper.toDto(card, owner))
                .collect(Collectors.toList());
    }

    public void blockCardByUser(String cardNumber, String authHeader) {

        Long clientId = jwtTokenUtil.getUserIdFromAuthHeader(authHeader);

        Card card = cardRepository.findByCardNumber(cardNumber)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Card not found with number: " + cardNumber));

        if (!card.getAccount().getClientId().equals(clientId)) {
            throw new UnauthorizedException("You can only block your own cards");
        }

        card.setStatus(CardStatus.BLOCKED);
        cardRepository.save(card);

        ClientDto owner = userClient.getClientById(card.getAccount().getClientId());
        EmailRequestDto emailRequestDto = new EmailRequestDto();
        emailRequestDto.setCode("CARD_BLOCKED");
        emailRequestDto.setDestination(owner.getEmail());
        rabbitTemplate.convertAndSend("card-status-change", emailRequestDto);
    }

    public void requestNewCard(CreateCardDto dto, String authHeader) throws JsonProcessingException {
        Long clientId = jwtTokenUtil.getUserIdFromAuthHeader(authHeader);

        Account account = accountRepository.findByAccountNumberAndClientId(dto.getAccountNumber(), clientId)
                .orElseThrow(() -> new AccNotFoundException("Account not found"));

        AccountTypeDto accountTypeDto = accountMapper.toAccountTypeDto(account);

        Long cardCount = cardRepository.countByAccount(account);

        if ((isBusiness(accountTypeDto) && cardCount > 0) || (!isBusiness(accountTypeDto) && cardCount > 2)) {
            throw new CardLimitExceededException(accountTypeDto.getAccountNumber());
        }
        if (dto.getCardLimit() != null && dto.getCardLimit().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidCardLimitException();
        }

        CardRequest cardRequest = new CardRequest();
        cardRequest.setClientId(clientId);
        cardRequest.setAccountNumber(account.getAccountNumber());
        cardRequest.setCardType(dto.getType());
        cardRequest.setCardIssuer(dto.getIssuer());
        cardRequest.setCardLimit(dto.getCardLimit());
        cardRequest.setName(dto.getName());
        cardRequest.setStatus(RequestStatus.PENDING);
        cardRequestRepository.save(cardRequest);

        CardVerificationDetailsDto cardVerificationDetailsDto = new CardVerificationDetailsDto();
        cardVerificationDetailsDto.setAccountNumber(account.getAccountNumber());
        cardVerificationDetailsDto.setType(dto.getType());
        cardVerificationDetailsDto.setIssuer(dto.getIssuer());
        cardVerificationDetailsDto.setName(dto.getName());

        CreateVerificationRequestDto verificationRequest = CreateVerificationRequestDto.builder()
                .userId(clientId)
                .targetId(cardRequest.getId())
                .verificationType(VerificationType.CARD_REQUEST)
                .details(objectMapper.writeValueAsString(cardVerificationDetailsDto))
                .build();

        userClient.createVerificationRequest(verificationRequest);

        log.info("Card request sent for verification for client {}", clientId);
    }


    public void approveCardRequest(Long id) {
        CardRequest cardRequest = cardRequestRepository.findById(id)
                .orElseThrow(EntityNotFoundException::new);

        if (cardRequest.getStatus() != RequestStatus.PENDING) {
            log.info("Card request {} is not active.", id);
            throw new EntityNotFoundException();
        }

        Account account = accountRepository.findByAccountNumberAndClientId(cardRequest.getAccountNumber(), cardRequest.getClientId())
                .orElseThrow(() -> new AccNotFoundException("Account not found"));


        AccountTypeDto accountTypeDto = accountMapper.toAccountTypeDto(account);
        Long cardCount = cardRepository.countByAccount(account);

        if ((isBusiness(accountTypeDto) && cardCount > 0) || (!isBusiness(accountTypeDto) && cardCount > 2)) {
            throw new CardLimitExceededException(accountTypeDto.getAccountNumber());
        }

        cardRequest.setStatus(RequestStatus.APPROVED);
        cardRequestRepository.save(cardRequest);

        Card card = new Card();
        card.setAccount(account);
        card.setName(cardRequest.getName());
        card.setIssuer(cardRequest.getCardIssuer());
        card.setType(cardRequest.getCardType());
        card.setCardNumber(generateCardNumber(cardRequest.getCardIssuer()));
        card.setCvv(generateCVV());
        card.setCreationDate(LocalDate.now());
        card.setExpirationDate(LocalDate.now().plusYears(4));
        card.setStatus(CardStatus.ACTIVE);
        card.setCardLimit(cardRequest.getCardLimit());

        cardRepository.save(card);

        log.info("Card created for request {} and client {}", id, cardRequest.getClientId());
    }

    public void rejectCardRequest(Long id) {
        CardRequest cardRequest = cardRequestRepository.findById(id)
                .orElseThrow(() -> new CardNotFoundException(String.valueOf(id)));
        if (!cardRequest.getStatus().equals(RequestStatus.PENDING))
            throw new RejectNonPendingRequestException();

        cardRequest.setStatus(RequestStatus.REJECTED);
        cardRequestRepository.save(cardRequest);
    }

}
