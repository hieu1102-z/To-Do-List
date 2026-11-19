package com.finalexam.to_do_list.model.repository

import com.finalexam.to_do_list.model.remote.AuthUser

/** Account creation is not retried when profile update or verification delivery fails. */
data class RegistrationResult(val user: AuthUser, val profileUpdated: Boolean, val verificationSent: Boolean)
