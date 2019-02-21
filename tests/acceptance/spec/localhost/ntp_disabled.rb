require_relative '../spec_helper'
describe service('ntp') do
    it { should_not be_enabled }
end
