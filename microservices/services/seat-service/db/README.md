# Cabin lookup index

Apply `cabin-lookup-index.sql` to `airline_seat_db` before deploying the optimized
booking service. The script is safe to reapply and adds a non-unique online index
on `(flight_instance_id, cabin_class_id)`. It does not change seat or booking data.
The JPA entity declares the same index for schema generation.

For an existing local MySQL database, start MySQL first and run:

```powershell
& 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe' -u root -p airline_seat_db --execute="source microservices/services/seat-service/db/cabin-lookup-index.sql"
```

Enter the password interactively. The VPS release script applies this migration
automatically before restarting the application services.
