package com.naqqa.chat.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "chat_members")
@CompoundIndexes({
        @CompoundIndex(name = "idx_chat_member_user", def = "{'_id.userId': 1}"),
        @CompoundIndex(name = "idx_chat_member_conv", def = "{'_id.conversationId': 1}")
})
@Getter
@Setter
public class ChatMemberEntity {

    @Id
    private ChatMemberKey id;

    private LocalDateTime joinedAt;
}
