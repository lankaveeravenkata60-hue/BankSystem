import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Bank Account Management System (PS-03): loads accounts and a day of transactions
 * from text files, applies them, then writes a statement and an error log.
 *
 * <p>Input : input/accounts.txt, input/transactions.txt<br>
 * Output: output/statement.txt (overwritten), output/errors.txt (appended)</p>
 */
public class Main {

    // ------------------------------------------------------------------ constants

    private static final String ACCOUNTS_FILE = "input/accounts.txt";
    private static final String TRANSACTIONS_FILE = "input/transactions.txt";
    private static final String OUTPUT_DIR = "output";
    private static final String STATEMENT_FILE = "output/statement.txt";
    private static final String ERRORS_FILE = "output/errors.txt";

    /** Upper limit for the record count N, so a corrupt file cannot exhaust memory. */
    private static final int MAX_RECORDS = 1_000_000;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ------------------------------------------------------------------ run state

    private BufferedWriter statement;
    private BufferedWriter errorLog;

    private int recordsRead;
    private int recordsAccepted;
    private int recordsRejected;

    // ------------------------------------------------------------------ entry point

    public static void main(String[] args) {
        try {
            new Main().run();
            System.out.println("Processing complete. See output folder.");
        } catch (IOException e) {
            System.out.println("Processing failed: " + oneLine(e.getMessage()));
        } catch (RuntimeException e) {
            System.out.println("Processing failed unexpectedly: " + e);
        }
    }

    // ------------------------------------------------------------------ top-level flow

    private void run() throws IOException {
        File outDir = new File(OUTPUT_DIR);
        if (!outDir.exists() && !outDir.mkdirs() && !outDir.isDirectory()) {
            throw new IOException("Cannot create output directory: " + outDir.getPath());
        }

        File statementFile = new File(STATEMENT_FILE);
        File errorsFile = new File(ERRORS_FILE);

        // Both streams are closed automatically; statement overwrites, errors appends.
        try (BufferedWriter st = new BufferedWriter(new FileWriter(statementFile));
             BufferedWriter el = new BufferedWriter(new FileWriter(errorsFile, true))) {

            statement = st;
            errorLog = el;
            String runTime = LocalDateTime.now().format(TIMESTAMP);

            errorLog.write("=== Run at " + runTime + " ===");
            errorLog.newLine();

            statement.write("==============================================================");
            statement.newLine();
            statement.write(" " + BankAccount.bankName + " - Daily Statement");
            statement.newLine();
            statement.write(" Run at: " + runTime);
            statement.newLine();
            statement.write("==============================================================");
            statement.newLine();

            BankAccount[] accounts = loadAccounts();
            processTransactions(accounts);
            writeClosingSection(accounts);

            statement.flush();
            errorLog.flush();
        }
    }

    // ------------------------------------------------------------------ input file checks

    /**
     * Checks the file with java.io.File before it is opened.
     *
     * @throws FileNotFoundException   if the file is missing, not a file, or not readable
     * @throws EmptyInputFileException if the file has zero length
     */
    private static File checkInputFile(String path) throws FileNotFoundException, EmptyInputFileException {
        File file = new File(path);
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            throw new FileNotFoundException(
                    "Input file is missing, is not a regular file or cannot be read: " + path);
        }
        if (file.length() <= 0) {
            throw new EmptyInputFileException("Input file is empty: " + path);
        }
        return file;
    }

    /** Reads the first record line, which holds N, the number of record lines that follow. */
    private static int readRecordCount(LineSource source, String path)
            throws IOException, EmptyInputFileException {
        String first = source.next();
        if (first == null) {
            throw new EmptyInputFileException("No record count N found in " + path);
        }
        int n;
        try {
            n = Integer.parseInt(first);
        } catch (NumberFormatException e) {
            throw new EmptyInputFileException(
                    "Record count N is not a valid number in " + path + ": '" + first + "'", e);
        }
        if (n <= 0) {
            throw new EmptyInputFileException("Record count N is zero or negative in " + path);
        }
        if (n > MAX_RECORDS) {
            throw new EmptyInputFileException(
                    "Record count N is larger than the supported maximum (" + MAX_RECORDS + ") in " + path);
        }
        return n;
    }

    // ------------------------------------------------------------------ loading accounts

    /**
     * Loads accounts.txt into one array of the parent type. Rejected records leave a
     * null slot. A missing or empty file is logged and gives an empty array.
     */
    private BankAccount[] loadAccounts() throws IOException {
        writeSection("ACCOUNT RECORDS (" + ACCOUNTS_FILE + ")");
        try {
            File file = checkInputFile(ACCOUNTS_FILE);
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                LineSource source = new LineSource(br);
                int n = readRecordCount(source, ACCOUNTS_FILE);
                BankAccount[] accounts = new BankAccount[n];

                int slot = 0;
                String record;
                while (slot < n && (record = source.next()) != null) {
                    int lineNo = source.getLineNo();
                    try {
                        accounts[slot] = parseAccount(record, accounts);
                        accept(lineNo, record, "created " + accounts[slot].getClass().getSimpleName());
                    } catch (InvalidBalanceException | InvalidRecordException e) {
                        reject(lineNo, record, e);
                    } catch (RuntimeException e) {
                        reject(lineNo, record, e);
                    } finally {
                        recordsRead++;
                        slot++;
                    }
                }
                reportExtraRecords(source, ACCOUNTS_FILE, n);
                return accounts;
            }
        } catch (FileNotFoundException | EmptyInputFileException e) {
            logFileError(ACCOUNTS_FILE, e);
            return new BankAccount[0];
        }
    }

    /**
     * Parses one account record: {@code B,accNo,name,balance} or
     * {@code S,accNo,name,balance,minBalance}.
     *
     * @throws InvalidRecordException  wrong field count, unknown type, non-numeric value,
     *                                 empty name or duplicate account number
     * @throws InvalidBalanceException the balance or minimum balance is negative
     */
    private static BankAccount parseAccount(String record, BankAccount[] loaded)
            throws InvalidRecordException, InvalidBalanceException {
        String[] f = splitTrim(record);
        try {
            String type = f[0].toUpperCase(Locale.ROOT);
            switch (type) {
                case "B": {
                    requireFieldCount(f, 4, "B,accNo,name,balance");
                    int accNo = Integer.parseInt(f[1]);
                    String name = requireName(f[2]);
                    double balance = parseMoney(f[3]);
                    requireNewAccountNumber(loaded, accNo);
                    return new BankAccount(accNo, name, balance);
                }
                case "S": {
                    requireFieldCount(f, 5, "S,accNo,name,balance,minBalance");
                    int accNo = Integer.parseInt(f[1]);
                    String name = requireName(f[2]);
                    double balance = parseMoney(f[3]);
                    double minBalance = parseMoney(f[4]);
                    requireNewAccountNumber(loaded, accNo);
                    return new SavingsAccount(accNo, name, balance, minBalance);
                }
                default:
                    throw new InvalidRecordException("Unknown account type code '" + f[0] + "'");
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            // Chaining: the parsing problem becomes an InvalidRecordException with the original as cause.
            throw new InvalidRecordException("Malformed account record: " + oneLine(e.getMessage()), e);
        }
    }

    // ------------------------------------------------------------------ processing transactions

    /** Processes transactions.txt in file order against the loaded accounts. */
    private void processTransactions(BankAccount[] accounts) throws IOException {
        writeSection("TRANSACTIONS (" + TRANSACTIONS_FILE + ")");
        try {
            File file = checkInputFile(TRANSACTIONS_FILE);
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                LineSource source = new LineSource(br);
                int n = readRecordCount(source, TRANSACTIONS_FILE);

                int processed = 0;
                String record;
                while (processed < n && (record = source.next()) != null) {
                    int lineNo = source.getLineNo();
                    try {
                        String result = applyTransaction(record, accounts);
                        accept(lineNo, record, result);
                    } catch (MinimumBalanceViolationException e) {
                        // most specific first (subclass of InsufficientBalanceException)
                        reject(lineNo, record, e);
                    } catch (InsufficientBalanceException e) {
                        reject(lineNo, record, e);
                    } catch (InvalidAmountException | InvalidInterestRateException
                             | InvalidRecordException | AccountNotFoundException e) {
                        reject(lineNo, record, e);
                    } catch (RuntimeException e) {
                        // most general last: anything unexpected is rejected, never fatal
                        reject(lineNo, record, e);
                    } finally {
                        recordsRead++;
                        processed++;
                    }
                }
                reportExtraRecords(source, TRANSACTIONS_FILE, n);
            }
        } catch (FileNotFoundException | EmptyInputFileException e) {
            logFileError(TRANSACTIONS_FILE, e);
        }
    }

    /**
     * Parses and applies one transaction: {@code accNo,D,amount}, {@code accNo,W,amount}
     * or {@code accNo,I,specialRate}. Works through the parent reference, so the child's
     * overridden methods run for savings accounts.
     *
     * @return a short description of what was done
     * @throws AccountNotFoundException (unchecked) if the account number was not loaded
     */
    private static String applyTransaction(String record, BankAccount[] accounts)
            throws InvalidRecordException, InvalidAmountException,
                   InvalidInterestRateException, InsufficientBalanceException {
        String[] f = splitTrim(record);
        int accNo;
        String code;
        double value;
        try {
            requireFieldCount(f, 3, "accNo,D|W|I,value");
            accNo = Integer.parseInt(f[0]);
            code = f[1].toUpperCase(Locale.ROOT);
            value = parseMoney(f[2]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new InvalidRecordException("Malformed transaction record: " + oneLine(e.getMessage()), e);
        }
        if (!code.equals("D") && !code.equals("W") && !code.equals("I")) {
            throw new InvalidRecordException("Unknown transaction type code '" + f[1] + "'");
        }

        BankAccount account = requireAccount(accounts, accNo); // may throw AccountNotFoundException

        switch (code) {
            case "D":
                account.deposit(value);
                return "deposited " + BankAccount.formatMoney(value)
                        + ", new balance " + BankAccount.formatMoney(account.getBalance());
            case "W":
                if (!account.withdraw(value)) {
                    throw withdrawalRefusal(account, value);
                }
                return "withdrew " + BankAccount.formatMoney(value)
                        + ", new balance " + BankAccount.formatMoney(account.getBalance());
            default: // "I"
                double interest = account.calculateInterest(value);
                return "interest quote at " + BankAccount.formatMoney(value) + "% = "
                        + BankAccount.formatMoney(interest)
                        + " (balance " + BankAccount.formatMoney(account.getBalance()) + ")";
        }
    }

    /**
     * Converts a {@code false} result of withdraw() into the matching exception: a
     * savings account that has enough money but would drop below its minimum balance
     * gets a MinimumBalanceViolationException, everything else an
     * InsufficientBalanceException.
     */
    private static InsufficientBalanceException withdrawalRefusal(BankAccount account, double amount) {
        String balanceText = BankAccount.formatMoney(account.getBalance());
        String amountText = BankAccount.formatMoney(amount);
        if (account instanceof SavingsAccount && amount <= account.getBalance()) {
            double min = ((SavingsAccount) account).getMinBalance();
            return new MinimumBalanceViolationException("Withdrawing " + amountText + " from account "
                    + account.getAccNo() + " (balance " + balanceText + ") would breach the minimum balance of "
                    + BankAccount.formatMoney(min));
        }
        return new InsufficientBalanceException("Withdrawing " + amountText + " from account "
                + account.getAccNo() + " exceeds the available balance of " + balanceText);
    }

    // ------------------------------------------------------------------ closing section

    /** Month-end work and report footer: balances, interest, penalties, comparison, totals. */
    private void writeClosingSection(BankAccount[] accounts) throws IOException {
        writeSection("CLOSING STATEMENT (interest is calculated on the closing balance, before any penalty)");

        BankAccount first = null;
        BankAccount second = null;

        for (BankAccount account : accounts) {
            if (account == null) {
                continue; // rejected record
            }
            if (first == null) {
                first = account;
            } else if (second == null) {
                second = account;
            }

            statement.write("[" + account.getClass().getSimpleName() + "] ");
            account.display(statement); // child version runs for savings accounts
            statement.newLine();
            statement.write("    Interest (calculateInterest): "
                    + BankAccount.formatMoney(account.calculateInterest()));
            statement.newLine();

            if (account instanceof SavingsAccount) {
                SavingsAccount savings = (SavingsAccount) account;
                double before = savings.getBalance();
                savings.applyPenalty();
                double penalty = before - savings.getBalance();
                statement.write("    Penalty (applyPenalty): " + BankAccount.formatMoney(penalty)
                        + " | Balance after penalty: " + BankAccount.formatMoney(savings.getBalance()));
                statement.newLine();
            }
        }

        writeSection("SUMMARY");
        if (first != null && second != null) {
            statement.write("compareBalance of first two accounts (" + first.getAccNo() + " vs "
                    + second.getAccNo() + "): higher balance is account " + first.compareBalance(second));
        } else {
            statement.write("compareBalance: not available (fewer than two accounts loaded)");
        }
        statement.newLine();

        int richest = BankAccount.findRichestCustomer(accounts);
        statement.write("Richest customer (findRichestCustomer): "
                + (richest == -1 ? "none (no accounts loaded)" : String.valueOf(richest)));
        statement.newLine();

        statement.write("Final accountCount: " + BankAccount.accountCount);
        statement.newLine();

        statement.write("Records read: " + recordsRead + ", accepted: " + recordsAccepted
                + ", rejected: " + recordsRejected + ".");
        statement.newLine();
    }

    // ------------------------------------------------------------------ report and log writers

    private void writeSection(String title) throws IOException {
        statement.newLine();
        statement.write("--- " + title + " ---");
        statement.newLine();
    }

    private void accept(int lineNo, String record, String detail) throws IOException {
        recordsAccepted++;
        statement.write("[line " + lineNo + "] ACCEPTED: " + record + " -> " + detail);
        statement.newLine();
    }

    /** Counts the rejection, writes the REJECTED line to the statement and one line to errors.txt. */
    private void reject(int lineNo, String record, Exception e) throws IOException {
        recordsRejected++;
        statement.write("[line " + lineNo + "] REJECTED: " + record + " -> "
                + e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()));
        statement.newLine();

        String cause = "";
        if (e.getCause() != null) {
            cause = " (cause: " + e.getCause().getClass().getSimpleName() + ": "
                    + oneLine(e.getCause().getMessage()) + ")";
        }
        errorLog.write("Line " + lineNo + " | Record: " + record + " | Exception: "
                + e.getClass().getSimpleName() + " | Message: " + oneLine(e.getMessage()) + cause);
        errorLog.newLine();
    }

    /** Logs a problem with a whole input file (missing, empty, bad record count). */
    private void logFileError(String path, Exception e) throws IOException {
        statement.write("FILE ERROR: " + path + " -> " + e.getClass().getSimpleName()
                + ": " + oneLine(e.getMessage()));
        statement.newLine();
        errorLog.write("File: " + path + " | Exception: " + e.getClass().getSimpleName()
                + " | Message: " + oneLine(e.getMessage()));
        errorLog.newLine();
    }

    /** Warns (once per file) when the file holds more record lines than the declared N. */
    private void reportExtraRecords(LineSource source, String path, int n) throws IOException {
        int extra = 0;
        while (source.next() != null) {
            extra++;
        }
        if (extra > 0) {
            errorLog.write("File: " + path + " | WARNING: " + extra
                    + " record line(s) after the declared count N=" + n + " were ignored");
            errorLog.newLine();
        }
    }

    // ------------------------------------------------------------------ parsing helpers

    private static String[] splitTrim(String record) {
        String[] fields = record.split(",", -1); // -1 keeps trailing empty fields
        for (int i = 0; i < fields.length; i++) {
            fields[i] = fields[i].trim();
        }
        return fields;
    }

    private static void requireFieldCount(String[] fields, int expected, String layout)
            throws InvalidRecordException {
        if (fields.length != expected) {
            throw new InvalidRecordException("Expected " + expected + " fields (" + layout
                    + ") but found " + fields.length);
        }
    }

    private static String requireName(String name) throws InvalidRecordException {
        if (name.isEmpty()) {
            throw new InvalidRecordException("Holder name is empty");
        }
        return name;
    }

    /** Parses a finite number; NaN and Infinity are rejected like any other bad number. */
    private static double parseMoney(String text) {
        double value = Double.parseDouble(text);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new NumberFormatException("Not a finite number: " + text);
        }
        return value;
    }

    private static void requireNewAccountNumber(BankAccount[] accounts, int accNo)
            throws InvalidRecordException {
        if (findAccount(accounts, accNo) != null) {
            throw new InvalidRecordException("Duplicate account number " + accNo);
        }
    }

    /** @return the account with this number, or null if there is none */
    private static BankAccount findAccount(BankAccount[] accounts, int accNo) {
        for (BankAccount account : accounts) {
            if (account != null && account.getAccNo() == accNo) {
                return account;
            }
        }
        return null;
    }

    /** @throws AccountNotFoundException (unchecked) if no loaded account has this number */
    private static BankAccount requireAccount(BankAccount[] accounts, int accNo) {
        BankAccount account = findAccount(accounts, accNo);
        if (account == null) {
            throw new AccountNotFoundException("No account with number " + accNo + " was loaded from "
                    + ACCOUNTS_FILE);
        }
        return account;
    }

    /** Keeps log lines on one line; replaces a null message with a placeholder. */
    private static String oneLine(String text) {
        if (text == null) {
            return "(no message)";
        }
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    // ------------------------------------------------------------------ line reader

    /**
     * Wraps a BufferedReader: returns the next usable line (trimmed, not blank, not a
     * '#' comment) and remembers the physical line number in the file.
     */
    private static final class LineSource {

        private final BufferedReader reader;
        private int lineNo = 0;

        LineSource(BufferedReader reader) {
            this.reader = reader;
        }

        /** @return the next usable line, or null at the end of the file */
        String next() throws IOException {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo == 1 && line.startsWith("\uFEFF")) {
                    line = line.substring(1); // drop a UTF-8 byte order mark
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                return trimmed;
            }
            return null;
        }

        /** @return the physical line number of the line last returned by next() */
        int getLineNo() {
            return lineNo;
        }
    }

    // ================================================================== custom exceptions and account classes

    /**
     * Checked exception: a deposit or withdrawal amount is zero, negative or not a
     * finite number. The rejected value is stored and can be read back with
     * {@link #getRejectedValue()}.
     */
    public static class InvalidAmountException extends Exception {

        private static final long serialVersionUID = 1L;

        /** The amount that was rejected ({@code NaN} when no value was supplied). */
        private final double rejectedValue;

        /**
         * @param message description of the problem
         */
        public InvalidAmountException(String message) {
            super(message);
            this.rejectedValue = Double.NaN;
        }

        /**
         * @param message description of the problem
         * @param cause   the underlying exception that triggered this one
         */
        public InvalidAmountException(String message, Throwable cause) {
            super(message, cause);
            this.rejectedValue = Double.NaN;
        }

        /**
         * @param message       description of the problem
         * @param rejectedValue the amount that was rejected
         */
        public InvalidAmountException(String message, double rejectedValue) {
            super(message);
            this.rejectedValue = rejectedValue;
        }

        /**
         * @return the amount that was rejected ({@code NaN} if none was recorded)
         */
        public double getRejectedValue() {
            return rejectedValue;
        }
    }

    /**
     * Checked exception: a balance or minimum balance is negative (or not a finite number).
     */
    public static class InvalidBalanceException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param message description of the problem
         */
        public InvalidBalanceException(String message) {
            super(message);
        }

        /**
         * @param message description of the problem
         * @param cause   the underlying exception that triggered this one
         */
        public InvalidBalanceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Checked exception: an interest rate is negative (or not a finite number).
     */
    public static class InvalidInterestRateException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param message description of the problem
         */
        public InvalidInterestRateException(String message) {
            super(message);
        }

        /**
         * @param message description of the problem
         * @param cause   the underlying exception that triggered this one
         */
        public InvalidInterestRateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Checked exception: a withdrawal is larger than the available balance.
     */
    public static class InsufficientBalanceException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param message description of the problem
         */
        public InsufficientBalanceException(String message) {
            super(message);
        }

        /**
         * @param message description of the problem
         * @param cause   the underlying exception that triggered this one
         */
        public InsufficientBalanceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Checked exception (subclass of InsufficientBalanceException): a savings withdrawal would leave the balance below the required minimum balance.
     */
    public static class MinimumBalanceViolationException extends InsufficientBalanceException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message description of the problem
         */
        public MinimumBalanceViolationException(String message) {
            super(message);
        }

        /**
         * @param message description of the problem
         * @param cause   the underlying exception that triggered this one
         */
        public MinimumBalanceViolationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Unchecked exception: a transaction refers to an account number that was not loaded from accounts.txt.
     */
    public static class AccountNotFoundException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message description of the problem
         */
        public AccountNotFoundException(String message) {
            super(message);
        }

        /**
         * @param message description of the problem
         * @param cause   the underlying exception that triggered this one
         */
        public AccountNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Checked exception: an input line has the wrong number of fields, an unknown type code, or a non-numeric value.
     */
    public static class InvalidRecordException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param message description of the problem
         */
        public InvalidRecordException(String message) {
            super(message);
        }

        /**
         * @param message description of the problem
         * @param cause   the underlying exception that triggered this one
         */
        public InvalidRecordException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Checked exception: an input file exists but holds no records, or its record count N is missing, invalid or zero.
     */
    public static class EmptyInputFileException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param message description of the problem
         */
        public EmptyInputFileException(String message) {
            super(message);
        }

        /**
         * @param message description of the problem
         * @param cause   the underlying exception that triggered this one
         */
        public EmptyInputFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * An ordinary bank account (parent class).
     *
     * <p>Static counter rule: {@link #accountCount} goes up by exactly one for every
     * object that is created. Only the no-argument constructor increments it; every
     * other constructor reaches it through {@code this(...)} here or {@code super(...)}
     * in the child class. If a constructor rejects an invalid value, the object is
     * not kept, so the counter is rolled back and the failed object is not counted.</p>
     */
    public static class BankAccount {

        // ------------------------------------------------------------------ static members

        /** Name of the bank, written in the statement header. */
        public static String bankName = "National Bank";

        /** Base interest rate in percent per annum. */
        public static double baseInterestRate = 4.0;

        /** Number of account objects (parent and child) successfully created. */
        public static int accountCount = 0;

        // ------------------------------------------------------------------ instance variables

        private int accNo;
        private String holderName = "";
        private double balance;

        // ------------------------------------------------------------------ constructors

        /** Default constructor: the only place where {@link #accountCount} is incremented. */
        public BankAccount() {
            accountCount++;
        }

        /**
         * @param accNo      account number
         * @param holderName name of the account holder
         */
        public BankAccount(int accNo, String holderName) {
            this();
            setAccNo(accNo);
            setHolderName(holderName);
        }

        /**
         * @param accNo      account number
         * @param holderName name of the account holder
         * @param balance    opening balance (must be a finite number &gt;= 0)
         * @throws InvalidBalanceException if the balance is negative or not finite
         */
        public BankAccount(int accNo, String holderName, double balance) throws InvalidBalanceException {
            this(accNo, holderName);
            try {
                setBalance(balance);
            } catch (InvalidBalanceException e) {
                // The object is not usable, so it must not stay in the account count.
                accountCount--;
                throw e;
            }
        }

        // ------------------------------------------------------------------ getters and validated setters

        public int getAccNo() {
            return accNo;
        }

        public final void setAccNo(int accNo) {
            this.accNo = accNo;
        }

        public String getHolderName() {
            return holderName;
        }

        /** Stores the trimmed name; a {@code null} name is stored as an empty string. */
        public final void setHolderName(String holderName) {
            this.holderName = (holderName == null) ? "" : holderName.trim();
        }

        public double getBalance() {
            return balance;
        }

        /**
         * Validated setter: the value is stored only if it is a finite number &gt;= 0.
         *
         * @throws InvalidBalanceException if the balance is negative, NaN or infinite
         */
        public final void setBalance(double balance) throws InvalidBalanceException {
            if (Double.isNaN(balance) || Double.isInfinite(balance) || balance < 0) {
                throw new InvalidBalanceException(
                        "Invalid balance " + balance + ": a balance must be a finite number >= 0");
            }
            this.balance = balance;
        }

        /**
         * Validated static setter for the base interest rate.
         *
         * @throws InvalidInterestRateException if the rate is negative, NaN or infinite
         */
        public static void setBaseInterestRate(double rate) throws InvalidInterestRateException {
            if (Double.isNaN(rate) || Double.isInfinite(rate) || rate < 0) {
                throw new InvalidInterestRateException(
                        "Invalid base interest rate " + rate + ": a rate must be a finite number >= 0");
            }
            baseInterestRate = rate;
        }

        // ------------------------------------------------------------------ business methods

        /**
         * Adds money to the account.
         *
         * @param amount amount to deposit (must be a finite number &gt; 0)
         * @throws InvalidAmountException if the amount is not positive; the balance is unchanged
         */
        public void deposit(double amount) throws InvalidAmountException {
            if (isInvalidAmount(amount)) {
                throw new InvalidAmountException(
                        "Deposit amount must be a finite number greater than zero: " + amount, amount);
            }
            balance += amount;
        }

        /**
         * Takes money out of the account.
         *
         * @param amount amount to withdraw (must be a finite number &gt; 0)
         * @return {@code true} if the money was withdrawn, {@code false} if the amount is
         *         larger than the balance (the balance is unchanged)
         * @throws InvalidAmountException if the amount is not positive
         */
        public boolean withdraw(double amount) throws InvalidAmountException {
            if (isInvalidAmount(amount)) {
                throw new InvalidAmountException(
                        "Withdrawal amount must be a finite number greater than zero: " + amount, amount);
            }
            if (amount <= balance) {
                balance -= amount;
                return true;
            }
            return false;
        }

        /** @return interest at the base rate: balance x baseInterestRate / 100 */
        public double calculateInterest() {
            return balance * baseInterestRate / 100.0;
        }

        /**
         * Interest at a special (for example senior citizen or promotional) rate.
         *
         * @param specialRate rate in percent per annum (must be a finite number &gt;= 0)
         * @return balance x specialRate / 100
         * @throws InvalidInterestRateException if the rate is negative, NaN or infinite
         */
        public double calculateInterest(double specialRate) throws InvalidInterestRateException {
            if (Double.isNaN(specialRate) || Double.isInfinite(specialRate) || specialRate < 0) {
                throw new InvalidInterestRateException(
                        "Interest rate must be a finite number >= 0: " + specialRate);
            }
            return balance * specialRate / 100.0;
        }

        /**
         * @param other the account to compare with
         * @return the account number of the account with the higher balance; if the
         *         balances are equal (or {@code other} is null) this account's number
         */
        public int compareBalance(BankAccount other) {
            if (other == null || this.balance >= other.balance) {
                return this.accNo;
            }
            return other.accNo;
        }

        /**
         * Writes account number, holder name and balance (2 decimals) on one line of
         * text. No line break is written, so a child class can append more fields to
         * the same line; the caller ends the line with {@code newLine()}.
         */
        public void display(BufferedWriter bw) throws IOException {
            bw.write("AccNo: " + accNo
                    + " | Holder: " + holderName
                    + " | Balance: " + formatMoney(balance));
        }

        /**
         * Deducts up to {@code amount} from the balance without ever going below zero.
         * Used by the child class for the month-end penalty.
         *
         * @return the amount that was actually deducted
         */
        protected double deductUpTo(double amount) {
            if (isInvalidAmount(amount) || balance <= 0) {
                return 0.0;
            }
            double deducted = Math.min(amount, balance);
            balance -= deducted;
            return deducted;
        }

        // ------------------------------------------------------------------ static helpers

        /** @return {@code true} if the amount is NaN, infinite, zero or negative */
        protected static boolean isInvalidAmount(double amount) {
            return Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0;
        }

        /** Formats a money value with two decimals, always with a '.' separator. */
        public static String formatMoney(double value) {
            return String.format(Locale.US, "%.2f", value);
        }

        /**
         * Finds the account with the highest balance. {@code null} elements (created by
         * skipped or rejected records) are ignored. On a tie the first account wins.
         *
         * @param accounts array of accounts (may contain nulls)
         * @return the account number of the richest customer, or -1 if there is no account
         */
        public static int findRichestCustomer(BankAccount[] accounts) {
            if (accounts == null) {
                return -1;
            }
            BankAccount richest = null;
            for (BankAccount account : accounts) {
                if (account == null) {
                    continue;
                }
                if (richest == null || account.balance > richest.balance) {
                    richest = account;
                }
            }
            return (richest == null) ? -1 : richest.accNo;
        }
    }

    /**
     * A savings account: earns bonus interest but must keep a minimum balance,
     * otherwise a penalty is charged at month end.
     */
    public static class SavingsAccount extends BankAccount {

        // ------------------------------------------------------------------ static members

        /** Bonus interest rate in percent, added to the base rate. */
        public static double bonusRate = 1.5;

        /** Penalty charged when the balance is below the minimum balance. */
        public static double penaltyAmount = 100.0;

        // ------------------------------------------------------------------ instance variables

        private double minBalance;

        // ------------------------------------------------------------------ constructors

        /** Default constructor (counted once, by the parent's no-argument constructor). */
        public SavingsAccount() {
            super();
        }

        /**
         * @param accNo      account number
         * @param holderName name of the account holder
         * @param balance    opening balance (must be &gt;= 0)
         * @param minBalance minimum balance (must be &gt;= 0)
         * @throws InvalidBalanceException if the balance or the minimum balance is invalid
         */
        public SavingsAccount(int accNo, String holderName, double balance, double minBalance)
                throws InvalidBalanceException {
            // The minimum balance is checked first, before any object is counted.
            super(accNo, holderName, requireValidMinBalance(minBalance, balance));
            this.minBalance = minBalance;
        }

        /** Validates the minimum balance and passes the opening balance through. */
        private static double requireValidMinBalance(double minBalance, double balance)
                throws InvalidBalanceException {
            if (Double.isNaN(minBalance) || Double.isInfinite(minBalance) || minBalance < 0) {
                throw new InvalidBalanceException(
                        "Invalid minimum balance " + minBalance + ": it must be a finite number >= 0");
            }
            return balance;
        }

        // ------------------------------------------------------------------ getter and validated setters

        public double getMinBalance() {
            return minBalance;
        }

        /**
         * @throws InvalidBalanceException if the minimum balance is negative, NaN or infinite
         */
        public void setMinBalance(double minBalance) throws InvalidBalanceException {
            requireValidMinBalance(minBalance, 0);
            this.minBalance = minBalance;
        }

        /**
         * Validated static setter for the bonus rate.
         *
         * @throws InvalidInterestRateException if the rate is negative, NaN or infinite
         */
        public static void setBonusRate(double rate) throws InvalidInterestRateException {
            if (Double.isNaN(rate) || Double.isInfinite(rate) || rate < 0) {
                throw new InvalidInterestRateException(
                        "Invalid bonus rate " + rate + ": a rate must be a finite number >= 0");
            }
            bonusRate = rate;
        }

        // ------------------------------------------------------------------ overridden methods

        /**
         * Permits the withdrawal only if (balance - amount) &gt;= minBalance.
         *
         * @return {@code true} if the money was withdrawn, {@code false} if the withdrawal
         *         would leave the balance below the minimum (the balance is unchanged)
         * @throws InvalidAmountException if the amount is not positive
         */
        @Override
        public boolean withdraw(double amount) throws InvalidAmountException {
            if (isInvalidAmount(amount)) {
                throw new InvalidAmountException(
                        "Withdrawal amount must be a finite number greater than zero: " + amount, amount);
            }
            if (getBalance() - amount >= minBalance) {
                return super.withdraw(amount);
            }
            return false;
        }

        /** @return base interest plus bonus interest: super + balance x bonusRate / 100 */
        @Override
        public double calculateInterest() {
            return super.calculateInterest() + (getBalance() * bonusRate / 100.0);
        }

        /**
         * Month-end penalty: if balance &lt; minBalance, deducts {@link #penaltyAmount}
         * (at most the balance, so it never becomes negative); otherwise does nothing.
         */
        public void applyPenalty() {
            if (getBalance() < minBalance) {
                deductUpTo(penaltyAmount);
            }
        }

        /** Writes the parent's fields, then appends the minimum balance to the same line. */
        @Override
        public void display(BufferedWriter bw) throws IOException {
            super.display(bw);
            bw.write(" | Min Balance: " + formatMoney(minBalance));
        }
    }

}
