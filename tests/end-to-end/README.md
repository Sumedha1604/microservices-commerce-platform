# End-to-End Tests

## Payment-creation failure compensation

Checkout payment-failure compensation is covered by unit tests. A full E2E test cannot currently force Payment creation to fail through the public APIs: the Payment service has no provider rejection behavior or supported failure configuration. Do not add a fake endpoint or configuration toggle solely for tests. Revisit this E2E case when a real payment provider or rejection behavior exists.
