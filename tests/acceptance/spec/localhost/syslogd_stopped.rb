require_relative '../spec_helper'
describe service('syslogd') do
    it { should_not be_running }
end
