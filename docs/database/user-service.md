# User Service database

Migration `V1__create_user_schema.sql` creates `user_profiles`, `addresses`, and `user_preferences`. Foreign keys are internal to this service only. `auth_user_id` is unique but deliberately has no Auth Service foreign key.
