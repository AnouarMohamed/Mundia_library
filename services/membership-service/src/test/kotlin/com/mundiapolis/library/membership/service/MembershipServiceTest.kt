package com.mundiapolis.library.membership.service

import com.mundiapolis.library.membership.dto.AccountStatus
import com.mundiapolis.library.membership.dto.IdentityEvidenceRef
import com.mundiapolis.library.membership.dto.MemberEligibility
import com.mundiapolis.library.membership.dto.MemberProfile
import com.mundiapolis.library.membership.dto.MembershipRole
import com.mundiapolis.library.membership.service.impl.MembershipServiceImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit.jupiter.SpringExtension
import kotlinx.coroutines.runBlocking

@ExtendWith(SpringExtension::class)
@SpringBootTest
class MembershipServiceTest {

    private val service = MembershipServiceImpl()

    @Test
    fun `test get member profile returns correct data`() = runBlocking {
        val profile = service.getMemberProfile("test-member-001")
        assertNotNull(profile)
        assertEquals("test-member-001", profile?.memberId)
        assertEquals("test@user.com", profile?.email)
        assertEquals("Test User", profile?.fullName)
        assertEquals(12345, profile?.universityId)
        assertEquals(AccountStatus.APPROVED, profile?.status)
        assertEquals(MembershipRole.USER, profile?.role)
    }

    @Test
    fun `test get member profile returns null for non-existent member`() = runBlocking {
        val profile = service.getMemberProfile("non-existent-member")
        assertNull(profile)
    }

    @Test
    fun `test check eligibility returns correct data`() = runBlocking {
        val eligibility = service.checkEligibility("test-member-001")
        assertEquals("test-member-001", eligibility.memberId)
        assertEquals(true, eligibility.eligible)
        assertEquals(AccountStatus.APPROVED, eligibility.status)
        assertEquals(5, eligibility.maxActiveLoans)
        assertEquals(0, eligibility.currentActiveLoans)
        assertEquals(false, eligibility.hasUnpaidOverdueFines)
    }

    @Test
    fun `test check eligibility returns rejected for non-existent member`() = runBlocking {
        val eligibility = service.checkEligibility("non-existent-member")
        assertEquals("non-existent-member", eligibility.memberId)
        assertEquals(false, eligibility.eligible)
        assertEquals(AccountStatus.REJECTED, eligibility.status)
        assertEquals("Member not found", eligibility.reason)
    }

    @Test
    fun `test get identity evidence ref returns null by default`() = runBlocking {
        val evidenceRef = service.getIdentityEvidenceRef("test-member-001")
        assertNull(evidenceRef)
    }
}
