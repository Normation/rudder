use crate::testlib::given::setup_state::TestSetup;
use crate::testlib::test_setup::TestSetupResult;
use anyhow::Error;
use log::debug;
use posix_acl::{PosixACL, Qualifier};

#[derive(Clone, Debug)]
#[repr(u32)]
pub enum Perms {
    READ = posix_acl::ACL_READ,
    WRITE = posix_acl::ACL_WRITE,
    EXECUTE = posix_acl::ACL_EXECUTE,
}
#[derive(Clone, Debug)]
pub struct PosixAclPresentStruct {
    pub path: String,
    pub qualifier: Qualifier,
    pub perm: Perms,
}
impl TestSetup for crate::testlib::given::posix_acl_present::PosixAclPresentStruct {
    fn resolve(&self) -> anyhow::Result<TestSetupResult, Error> {
        debug!(
            "Set POSIX ACL {:?} {:?} to file {}",
            self.qualifier, self.perm, self.path
        );
        let mut acl = PosixACL::read_acl(self.path.clone())?;
        acl.set(self.qualifier, self.perm.clone() as u32);
        acl.write_acl(self.path.clone())?;
        Ok(TestSetupResult::default())
    }
}
