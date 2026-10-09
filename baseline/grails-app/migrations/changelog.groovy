databaseChangeLog = {
    changeSet(author: 'chirp', id: 'baseline-001') {
        createTable(tableName: 'account') {
            column(name: 'id', type: 'bigint', autoIncrement: true) {
                constraints(primaryKey: true, nullable: false, primaryKeyName: 'account_pkey')
            }
            column(name: 'version', type: 'bigint') { constraints(nullable: false) }
            column(name: 'handle', type: 'varchar(40)') { constraints(nullable: false) }
            column(name: 'display_name', type: 'varchar(80)') { constraints(nullable: false) }
            column(name: 'bio', type: 'varchar(160)')
            column(name: 'joined_at', type: 'timestamp') { constraints(nullable: false) }
        }
        addUniqueConstraint(tableName: 'account', columnNames: 'handle', constraintName: 'account_handle_key')

        createTable(tableName: 'chirp') {
            column(name: 'id', type: 'bigint', autoIncrement: true) {
                constraints(primaryKey: true, nullable: false, primaryKeyName: 'chirp_pkey')
            }
            column(name: 'version', type: 'bigint') { constraints(nullable: false) }
            column(name: 'author_id', type: 'bigint') { constraints(nullable: false) }
            column(name: 'body', type: 'varchar(280)') { constraints(nullable: false) }
            column(name: 'posted_at', type: 'timestamp') { constraints(nullable: false) }
        }
        addForeignKeyConstraint(baseTableName: 'chirp', baseColumnNames: 'author_id',
                referencedTableName: 'account', referencedColumnNames: 'id', constraintName: 'chirp_author_fk')
        createIndex(tableName: 'chirp', indexName: 'chirp_author_posted_at_idx') {
            column(name: 'author_id')
            column(name: 'posted_at')
        }

        createTable(tableName: 'follow') {
            column(name: 'id', type: 'bigint', autoIncrement: true) {
                constraints(primaryKey: true, nullable: false, primaryKeyName: 'follow_pkey')
            }
            column(name: 'version', type: 'bigint') { constraints(nullable: false) }
            column(name: 'follower_id', type: 'bigint') { constraints(nullable: false) }
            column(name: 'followee_id', type: 'bigint') { constraints(nullable: false) }
            column(name: 'followed_at', type: 'timestamp') { constraints(nullable: false) }
        }
        addForeignKeyConstraint(baseTableName: 'follow', baseColumnNames: 'follower_id',
                referencedTableName: 'account', referencedColumnNames: 'id', constraintName: 'follow_follower_fk')
        addForeignKeyConstraint(baseTableName: 'follow', baseColumnNames: 'followee_id',
                referencedTableName: 'account', referencedColumnNames: 'id', constraintName: 'follow_followee_fk')
        addUniqueConstraint(tableName: 'follow', columnNames: 'follower_id,followee_id', constraintName: 'follow_pair_key')
        createIndex(tableName: 'follow', indexName: 'follow_follower_idx') { column(name: 'follower_id') }
        createIndex(tableName: 'follow', indexName: 'follow_followee_idx') { column(name: 'followee_id') }

        createTable(tableName: 'cheer') {
            column(name: 'id', type: 'bigint', autoIncrement: true) {
                constraints(primaryKey: true, nullable: false, primaryKeyName: 'cheer_pkey')
            }
            column(name: 'version', type: 'bigint') { constraints(nullable: false) }
            column(name: 'account_id', type: 'bigint') { constraints(nullable: false) }
            column(name: 'chirp_id', type: 'bigint') { constraints(nullable: false) }
            column(name: 'cheered_at', type: 'timestamp') { constraints(nullable: false) }
        }
        addForeignKeyConstraint(baseTableName: 'cheer', baseColumnNames: 'account_id',
                referencedTableName: 'account', referencedColumnNames: 'id', constraintName: 'cheer_account_fk')
        addForeignKeyConstraint(baseTableName: 'cheer', baseColumnNames: 'chirp_id',
                referencedTableName: 'chirp', referencedColumnNames: 'id', constraintName: 'cheer_chirp_fk')
        addUniqueConstraint(tableName: 'cheer', columnNames: 'account_id,chirp_id', constraintName: 'cheer_pair_key')
        createIndex(tableName: 'cheer', indexName: 'cheer_chirp_idx') { column(name: 'chirp_id') }
    }

    changeSet(author: 'chirp', id: 'baseline-002') {
        addColumn(tableName: 'chirp') {
            column(name: 'reply_to_id', type: 'bigint')
        }
        addForeignKeyConstraint(baseTableName: 'chirp', baseColumnNames: 'reply_to_id',
                referencedTableName: 'chirp', referencedColumnNames: 'id', constraintName: 'chirp_reply_to_fk')
        createIndex(tableName: 'chirp', indexName: 'chirp_reply_to_idx') { column(name: 'reply_to_id') }

        createTable(tableName: 'chirp_tag') {
            column(name: 'id', type: 'bigint', autoIncrement: true) {
                constraints(primaryKey: true, nullable: false, primaryKeyName: 'chirp_tag_pkey')
            }
            column(name: 'version', type: 'bigint') { constraints(nullable: false) }
            column(name: 'chirp_id', type: 'bigint') { constraints(nullable: false) }
            column(name: 'tag', type: 'varchar(40)') { constraints(nullable: false) }
        }
        addForeignKeyConstraint(baseTableName: 'chirp_tag', baseColumnNames: 'chirp_id',
                referencedTableName: 'chirp', referencedColumnNames: 'id', constraintName: 'chirp_tag_chirp_fk')
        createIndex(tableName: 'chirp_tag', indexName: 'chirp_tag_chirp_idx') { column(name: 'chirp_id') }

        createTable(tableName: 'mention') {
            column(name: 'id', type: 'bigint', autoIncrement: true) {
                constraints(primaryKey: true, nullable: false, primaryKeyName: 'mention_pkey')
            }
            column(name: 'version', type: 'bigint') { constraints(nullable: false) }
            column(name: 'chirp_id', type: 'bigint') { constraints(nullable: false) }
            column(name: 'mentioned_id', type: 'bigint') { constraints(nullable: false) }
        }
        addForeignKeyConstraint(baseTableName: 'mention', baseColumnNames: 'chirp_id',
                referencedTableName: 'chirp', referencedColumnNames: 'id', constraintName: 'mention_chirp_fk')
        addForeignKeyConstraint(baseTableName: 'mention', baseColumnNames: 'mentioned_id',
                referencedTableName: 'account', referencedColumnNames: 'id', constraintName: 'mention_account_fk')
        createIndex(tableName: 'mention', indexName: 'mention_chirp_idx') { column(name: 'chirp_id') }

        createTable(tableName: 'media') {
            column(name: 'id', type: 'bigint', autoIncrement: true) {
                constraints(primaryKey: true, nullable: false, primaryKeyName: 'media_pkey')
            }
            column(name: 'version', type: 'bigint') { constraints(nullable: false) }
            column(name: 'chirp_id', type: 'bigint') { constraints(nullable: false) }
            column(name: 'url', type: 'varchar(200)') { constraints(nullable: false) }
        }
        addForeignKeyConstraint(baseTableName: 'media', baseColumnNames: 'chirp_id',
                referencedTableName: 'chirp', referencedColumnNames: 'id', constraintName: 'media_chirp_fk')
        createIndex(tableName: 'media', indexName: 'media_chirp_idx') { column(name: 'chirp_id') }
    }
}
